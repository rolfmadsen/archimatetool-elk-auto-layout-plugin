package com.archimatetool.elk;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.editor.diagram.IArchimateDiagramEditor;
import com.archimatetool.model.IArchimateDiagramModel;

public class LayoutHandler extends AbstractHandler {

    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchPart part = HandlerUtil.getActivePart(event);

            if (part instanceof IArchimateDiagramEditor) {
                IArchimateDiagramEditor editor = (IArchimateDiagramEditor) part;
                IArchimateDiagramModel diagramModel = (IArchimateDiagramModel) editor.getModel();

                if (diagramModel != null) {
                    // 1. Build ELK Graph
                    ElkGraphBuilder builder = new ElkGraphBuilder();
                    org.eclipse.elk.graph.ElkNode rootNode = builder.buildGraph(diagramModel);

                    org.eclipse.jface.dialogs.MessageDialog.openInformation(
                            org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                            "ELK Layout Diagnostic",
                            "Starting ELK layout on " + diagramModel.getChildren().size() + " root objects.\n"
                                    + "Root node has " + rootNode.getChildren().size() + " Elk children.");

                    // 2. Execute Layout
                    org.eclipse.elk.core.RecursiveGraphLayoutEngine engine = new org.eclipse.elk.core.RecursiveGraphLayoutEngine();
                    engine.layout(rootNode, new org.eclipse.elk.core.util.BasicProgressMonitor());

                    // 3. Apply Coordinates via GEF Command
                    org.eclipse.gef.commands.CompoundCommand compoundCommand = new org.eclipse.gef.commands.CompoundCommand(
                            "Make ELK Layout");

                    int modifiedCount = 0;
                    for (com.archimatetool.model.IDiagramModelObject modelObject : diagramModel.getChildren()) {
                        if (applyBoundsCommand(builder, modelObject, compoundCommand)) {
                            modifiedCount++;
                        }
                    }

                    if (compoundCommand.canExecute()) {
                        org.eclipse.gef.commands.CommandStack stack = (org.eclipse.gef.commands.CommandStack) editor
                                .getAdapter(org.eclipse.gef.commands.CommandStack.class);
                        if (stack != null) {
                            stack.execute(compoundCommand);
                            org.eclipse.jface.dialogs.MessageDialog.openInformation(
                                    org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                                    "ELK Layout Diagnostic",
                                    "Layout complete!\nRoot graph size: " + rootNode.getWidth() + "x"
                                            + rootNode.getHeight() + "\nApplied coordinates to " + modifiedCount
                                            + " elements.");
                        }
                    } else {
                        org.eclipse.jface.dialogs.MessageDialog.openInformation(
                                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                                "ELK Layout",
                                "No layout commands to execute (compoundCommand.canExecute() is false or no bounds changed).");
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            String stackTrace = sw.toString();

            org.eclipse.jface.dialogs.MessageDialog.openError(
                    org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                    "ELK Layout Error", t.getClass().getName() + ": " + t.getMessage() + "\n\n"
                            + stackTrace.substring(0, Math.min(stackTrace.length(), 1000)));
        }

        return null;
    }

    private boolean applyBoundsCommand(ElkGraphBuilder builder, com.archimatetool.model.IDiagramModelObject dmo,
            org.eclipse.gef.commands.CompoundCommand compoundCommand) {
        boolean modified = false;
        org.eclipse.elk.graph.ElkNode elkNode = builder.getElkNode(dmo);
        if (elkNode != null) {
            // Calculate absolute coordinates by traversing up the parent hierarchy
            double x = elkNode.getX();
            double y = elkNode.getY();
            org.eclipse.elk.graph.ElkNode parent = elkNode.getParent();
            while (parent != null && parent.getParent() != null) { // rootNode has no parent
                x += parent.getX();
                y += parent.getY();
                parent = parent.getParent();
            }

            final com.archimatetool.model.IBounds oldBounds = dmo.getBounds();
            final com.archimatetool.model.IBounds newBounds = com.archimatetool.model.IArchimateFactory.eINSTANCE
                    .createBounds((int) x, (int) y, (int) elkNode.getWidth(), (int) elkNode.getHeight());

            final java.util.Map<com.archimatetool.model.IDiagramModelConnection, java.util.List<com.archimatetool.model.IDiagramModelBendpoint>> oldBendpoints = new java.util.HashMap<>();
            for (Object o : dmo.getSourceConnections()) {
                if (o instanceof com.archimatetool.model.IDiagramModelConnection) {
                    com.archimatetool.model.IDiagramModelConnection conn = (com.archimatetool.model.IDiagramModelConnection) o;
                    oldBendpoints.put(conn, new java.util.ArrayList<>(conn.getBendpoints()));
                }
            }

            org.eclipse.gef.commands.Command cmd = new org.eclipse.gef.commands.Command() {
                @Override
                public void execute() {
                    dmo.setBounds(newBounds);
                    // Clear bendpoints of source connections to allow straight/orthogonal routing
                    // automatically
                    for (Object o : dmo.getSourceConnections()) {
                        if (o instanceof com.archimatetool.model.IDiagramModelConnection) {
                            ((com.archimatetool.model.IDiagramModelConnection) o).getBendpoints().clear();
                        }
                    }
                    // Force refresh trigger as per Archi plugin docs
                    dmo.getFeatures().putString("refresh-trigger", "true");
                    dmo.getFeatures().remove("refresh-trigger");
                }

                @Override
                public void undo() {
                    dmo.setBounds(oldBounds);
                    for (Object o : dmo.getSourceConnections()) {
                        if (o instanceof com.archimatetool.model.IDiagramModelConnection) {
                            com.archimatetool.model.IDiagramModelConnection conn = (com.archimatetool.model.IDiagramModelConnection) o;
                            java.util.List<com.archimatetool.model.IDiagramModelBendpoint> bps = oldBendpoints
                                    .get(conn);
                            if (bps != null) {
                                conn.getBendpoints().clear();
                                conn.getBendpoints().addAll(bps);
                            }
                        }
                    }
                    dmo.getFeatures().putString("refresh-trigger", "true");
                    dmo.getFeatures().remove("refresh-trigger");
                }
            };
            compoundCommand.add(cmd);
            modified = true;
        }

        // Recursively apply to children
        if (dmo instanceof com.archimatetool.model.IDiagramModelContainer) {
            for (com.archimatetool.model.IDiagramModelObject child : ((com.archimatetool.model.IDiagramModelContainer) dmo)
                    .getChildren()) {
                if (applyBoundsCommand(builder, child, compoundCommand)) {
                    modified = true;
                }
            }
        }
        return modified;
    }
}
