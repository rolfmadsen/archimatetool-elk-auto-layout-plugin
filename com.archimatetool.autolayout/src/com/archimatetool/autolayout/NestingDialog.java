package com.archimatetool.autolayout;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.jface.viewers.ComboBoxCellEditor;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.editor.ui.ArchiLabelProvider;
import com.archimatetool.editor.ui.IArchiImages;
import com.archimatetool.editor.ui.components.ExtendedTitleAreaDialog;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelGroup;
import com.archimatetool.model.IGrouping;

/**
 * Dialog to ask the user how to handle nested elements before auto-layout.
 */
public class NestingDialog extends ExtendedTitleAreaDialog {

    public enum Action {
        DE_NESTIFY("De-nestify (Move children to top-level)"),
        FLATTEN("Flatten (Delete container, keep children)");

        private final String label;
        Action(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public static class NestingEntry {
        private final IDiagramModelContainer container;
        private Action action;

        public NestingEntry(IDiagramModelContainer container) {
            this.container = container;
            // Default logic: Groups and Groupings are flattened by default
            if (container instanceof IDiagramModelGroup) {
                this.action = Action.FLATTEN;
            } else if (container instanceof IDiagramModelArchimateObject) {
                if (((IDiagramModelArchimateObject) container).getArchimateElement() instanceof IGrouping) {
                    this.action = Action.FLATTEN;
                } else {
                    this.action = Action.DE_NESTIFY;
                }
            } else {
                this.action = Action.DE_NESTIFY;
            }
        }

        public IDiagramModelContainer getContainer() { return container; }
        public Action getAction() { return action; }
        public void setAction(Action action) { this.action = action; }
    }

    private final List<NestingEntry> entries;
    private TableViewer tableViewer;

    public NestingDialog(Shell parentShell, List<IDiagramModelContainer> containers) {
        super(parentShell, "NestingDialog");
        setTitleImage(IArchiImages.ImageFactory.getImage(IArchiImages.ECLIPSE_IMAGE_IMPORT_PREF_WIZARD));
        setShellStyle(getShellStyle() | SWT.RESIZE);
        
        this.entries = new ArrayList<>();
        for (IDiagramModelContainer container : containers) {
            this.entries.add(new NestingEntry(container));
        }
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Smart Auto-Layout: Nested Elements Detected");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("How should nested elements be handled?");
        setMessage("The auto-layout engine works best with flat views. Choose how to handle each container.");

        Composite composite = (Composite) super.createDialogArea(parent);
        Composite client = new Composite(composite, SWT.NULL);
        client.setLayout(new GridLayout(1, false));
        client.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite tableComp = new Composite(client, SWT.BORDER);
        TableColumnLayout tableLayout = new TableColumnLayout();
        tableComp.setLayout(tableLayout);
        tableComp.setLayoutData(new GridData(GridData.FILL_BOTH));

        tableViewer = new TableViewer(tableComp, SWT.FULL_SELECTION | SWT.BORDER);
        tableViewer.getTable().setHeaderVisible(true);
        tableViewer.getTable().setLinesVisible(true);
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());

        // Column 1: Container
        TableViewerColumn colContainer = new TableViewerColumn(tableViewer, SWT.NONE);
        colContainer.getColumn().setText("Container");
        tableLayout.setColumnData(colContainer.getColumn(), new ColumnWeightData(40, true));
        colContainer.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ArchiLabelProvider.INSTANCE.getLabel(((NestingEntry) element).getContainer());
            }
            @Override
            public Image getImage(Object element) {
                return ArchiLabelProvider.INSTANCE.getImage(((NestingEntry) element).getContainer());
            }
        });

        // Column 2: Action
        TableViewerColumn colAction = new TableViewerColumn(tableViewer, SWT.NONE);
        colAction.getColumn().setText("Action");
        tableLayout.setColumnData(colAction.getColumn(), new ColumnWeightData(60, true));
        colAction.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((NestingEntry) element).getAction().getLabel();
            }
        });

        colAction.setEditingSupport(new EditingSupport(tableViewer) {
            @Override
            protected CellEditor getCellEditor(Object element) {
                String[] labels = new String[Action.values().length];
                for (int i = 0; i < Action.values().length; i++) {
                    labels[i] = Action.values()[i].getLabel();
                }
                return new ComboBoxCellEditor(tableViewer.getTable(), labels, SWT.READ_ONLY);
            }

            @Override
            protected boolean canEdit(Object element) {
                return true;
            }

            @Override
            protected Object getValue(Object element) {
                return ((NestingEntry) element).getAction().ordinal();
            }

            @Override
            protected void setValue(Object element, Object value) {
                int index = (Integer) value;
                if (index >= 0) {
                    ((NestingEntry) element).setAction(Action.values()[index]);
                    tableViewer.update(element, null);
                }
            }
        });

        tableViewer.setInput(entries);

        return composite;
    }

    public List<NestingEntry> getEntries() {
        return entries;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Continue Layout", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
