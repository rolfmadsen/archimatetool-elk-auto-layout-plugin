package com.archimatetool.autolayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.editor.diagram.IArchimateDiagramEditor;
import com.archimatetool.model.*;

/**
 * Command handler for the ArchiMate Layout Engine.
 *
 * Invoked from the context menu on ArchiMate diagram editors.
 * Uses the custom ArchiMateGridEngine to compute a strict 2D grid
 * layout, then applies the computed bounds back to the diagram
 * via GEF commands (supporting undo/redo).
 */
public class LayoutHandler extends AbstractHandler {

    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchPart part = HandlerUtil.getActivePart(event);

            if (part instanceof IArchimateDiagramEditor) {
                IArchimateDiagramEditor editor = (IArchimateDiagramEditor) part;
                IArchimateDiagramModel diagramModel = (IArchimateDiagramModel) editor.getModel();

                if (diagramModel != null) {
                    // Check for nested elements
                    List<IDiagramModelContainer> nestedContainers = findNestedContainers(diagramModel);
                    org.eclipse.gef.commands.CompoundCommand preLayoutCommand = new org.eclipse.gef.commands.CompoundCommand("Pre-Layout Flattening");

                    if (!nestedContainers.isEmpty()) {
                        NestingDialog dialog = new NestingDialog(HandlerUtil.getActiveShell(event), nestedContainers);
                        if (dialog.open() != org.eclipse.jface.window.Window.OK) {
                            return null;
                        }

                        for (NestingDialog.NestingEntry entry : dialog.getEntries()) {
                            if (entry.getAction() == NestingDialog.Action.FLATTEN) {
                                flattenContainer(entry.getContainer(), preLayoutCommand);
                            } else if (entry.getAction() == NestingDialog.Action.DE_NESTIFY) {
                                denestifyContainer(entry.getContainer(), preLayoutCommand);
                            }
                        }
                    }

                    // Execute pre-layout commands if any
                    if (preLayoutCommand.canExecute()) {
                        org.eclipse.gef.commands.CommandStack stack = (org.eclipse.gef.commands.CommandStack) editor
                                .getAdapter(org.eclipse.gef.commands.CommandStack.class);
                        if (stack != null) {
                            stack.execute(preLayoutCommand);
                        }
                    }

                    // 1. Compute grid layout
                    ArchiMateGridEngine engine = new ArchiMateGridEngine();
                    Map<IDiagramModelObject, ArchiMateGridEngine.LayoutResult> layoutResults = engine
                            .computeLayout(diagramModel);

                    // 2. Build compound command (supports undo/redo)
                    org.eclipse.gef.commands.CompoundCommand compoundCommand = new org.eclipse.gef.commands.CompoundCommand(
                            "ArchiMate Grid Layout");

                    int modifiedCount = 0;
                    for (Map.Entry<IDiagramModelObject, ArchiMateGridEngine.LayoutResult> entry : layoutResults
                            .entrySet()) {
                        IDiagramModelObject dmo = entry.getKey();
                        ArchiMateGridEngine.LayoutResult result = entry.getValue();

                        if (applyLayoutResult(dmo, result, compoundCommand)) {
                            modifiedCount++;
                        }
                    }

                    // 3. Execute via command stack
                    if (compoundCommand.canExecute()) {
                        org.eclipse.gef.commands.CommandStack stack = (org.eclipse.gef.commands.CommandStack) editor
                                .getAdapter(
                                        org.eclipse.gef.commands.CommandStack.class);
                        if (stack != null) {
                            stack.execute(compoundCommand);
                            org.eclipse.jface.dialogs.MessageDialog.openInformation(
                                    org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                                    "ArchiMate Grid Layout",
                                    "Layout complete!\nPositioned " + modifiedCount
                                            + " elements on the ArchiMate grid.");
                        }
                    } else {
                        org.eclipse.jface.dialogs.MessageDialog.openInformation(
                                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                                "ArchiMate Grid Layout",
                                "No elements to layout.");
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
                    "ArchiMate Grid Layout Error",
                    t.getClass().getName() + ": " + t.getMessage() + "\n\n"
                            + stackTrace.substring(0, Math.min(stackTrace.length(), 1000)));
        }

        return null;
    }

    private List<IDiagramModelContainer> findNestedContainers(IDiagramModelContainer container) {
        List<IDiagramModelContainer> result = new ArrayList<>();
        for (IDiagramModelObject dmo : container.getChildren()) {
            if (dmo instanceof IDiagramModelContainer && !((IDiagramModelContainer) dmo).getChildren().isEmpty()) {
                result.add((IDiagramModelContainer) dmo);
                result.addAll(findNestedContainers((IDiagramModelContainer) dmo));
            }
        }
        return result;
    }

    private void flattenContainer(IDiagramModelContainer container, org.eclipse.gef.commands.CompoundCommand compoundCommand) {
        IDiagramModelContainer parent = (IDiagramModelContainer) ((IDiagramModelObject) container).eContainer();
        
        // Move children to parent
        for (IDiagramModelObject child : new ArrayList<>(container.getChildren())) {
            IBounds absBounds = getAbsoluteBounds(child);
            compoundCommand.add(new RemoveChildCommand(container, child));
            compoundCommand.add(new AddChildCommand(parent, child, absBounds));
        }
        
        // Delete container
        compoundCommand.add(com.archimatetool.editor.diagram.commands.DiagramCommandFactory.createDeleteDiagramObjectCommand((IDiagramModelObject) container, false));
    }

    private void denestifyContainer(IDiagramModelContainer container, org.eclipse.gef.commands.CompoundCommand compoundCommand) {
        IArchimateDiagramModel diagramModel = (IArchimateDiagramModel) container.getDiagramModel();
        
        // Move children to top-level
        for (IDiagramModelObject child : new ArrayList<>(container.getChildren())) {
            IBounds absBounds = getAbsoluteBounds(child);
            compoundCommand.add(new RemoveChildCommand(container, child));
            compoundCommand.add(new AddChildCommand(diagramModel, child, absBounds));
        }
    }

    private IBounds getAbsoluteBounds(IDiagramModelObject dmo) {
        IBounds bounds = dmo.getBounds();
        int x = bounds.getX();
        int y = bounds.getY();
        
        org.eclipse.emf.ecore.EObject parent = dmo.eContainer();
        while (parent instanceof IDiagramModelObject) {
            IBounds pb = ((IDiagramModelObject) parent).getBounds();
            x += pb.getX();
            y += pb.getY();
            parent = parent.eContainer();
        }
        
        return IArchimateFactory.eINSTANCE.createBounds(x, y, bounds.getWidth(), bounds.getHeight());
    }

    private static class RemoveChildCommand extends org.eclipse.gef.commands.Command {
        private IDiagramModelContainer container;
        private IDiagramModelObject child;
        private int index;

        public RemoveChildCommand(IDiagramModelContainer container, IDiagramModelObject child) {
            this.container = container;
            this.child = child;
        }

        @Override
        public void execute() {
            index = container.getChildren().indexOf(child);
            container.getChildren().remove(child);
        }

        @Override
        public void undo() {
            container.getChildren().add(index, child);
        }
    }

    private static class AddChildCommand extends org.eclipse.gef.commands.Command {
        private IDiagramModelContainer container;
        private IDiagramModelObject child;
        private IBounds oldBounds, newBounds;

        public AddChildCommand(IDiagramModelContainer container, IDiagramModelObject child, IBounds newBounds) {
            this.container = container;
            this.child = child;
            this.newBounds = newBounds;
            this.oldBounds = child.getBounds();
        }

        @Override
        public void execute() {
            child.setBounds(newBounds);
            container.getChildren().add(child);
        }

        @Override
        public void undo() {
            container.getChildren().remove(child);
            child.setBounds(oldBounds);
        }
    }

    /**
     * Creates a GEF command to apply computed layout bounds to a diagram object.
     * Also clears bendpoints on source connections to allow clean routing.
     * Supports full undo/redo.
     */
    private boolean applyLayoutResult(
            IDiagramModelObject dmo,
            ArchiMateGridEngine.LayoutResult result,
            org.eclipse.gef.commands.CompoundCommand compoundCommand) {

        final IBounds oldBounds = dmo.getBounds();
        final IBounds newBounds = IArchimateFactory.eINSTANCE.createBounds(
                result.x, result.y, result.width, result.height);

        // Save old bendpoints for undo
        final java.util.Map<IDiagramModelConnection, java.util.List<IDiagramModelBendpoint>> oldBendpoints = new java.util.HashMap<>();
        for (Object o : dmo.getSourceConnections()) {
            if (o instanceof IDiagramModelConnection) {
                IDiagramModelConnection conn = (IDiagramModelConnection) o;
                oldBendpoints.put(conn, new java.util.ArrayList<>(conn.getBendpoints()));
            }
        }

        org.eclipse.gef.commands.Command cmd = new org.eclipse.gef.commands.Command() {
            @Override
            public void execute() {
                dmo.setBounds(newBounds);
                // Clear all bendpoints — let connections route directly
                for (Object o : dmo.getSourceConnections()) {
                    if (o instanceof IDiagramModelConnection) {
                        ((IDiagramModelConnection) o).getBendpoints().clear();
                    }
                }
                // Force diagram refresh (per Archi plugin docs)
                dmo.getFeatures().putString("refresh-trigger", "true");
                dmo.getFeatures().remove("refresh-trigger");
            }

            @Override
            public void undo() {
                dmo.setBounds(oldBounds);
                // Restore original bendpoints
                for (Object o : dmo.getSourceConnections()) {
                    if (o instanceof IDiagramModelConnection) {
                        IDiagramModelConnection conn = (IDiagramModelConnection) o;
                        java.util.List<IDiagramModelBendpoint> bps = oldBendpoints.get(conn);
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
        return true;
    }
}
