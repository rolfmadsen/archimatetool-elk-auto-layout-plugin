package com.archimatetool.autolayout;

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
