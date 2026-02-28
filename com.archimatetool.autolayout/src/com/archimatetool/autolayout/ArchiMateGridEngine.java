package com.archimatetool.autolayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.archimatetool.editor.ArchiPlugin;
import com.archimatetool.editor.preferences.IPreferenceConstants;
import com.archimatetool.model.*;

/**
 * Custom ArchiMate 2D Grid Layout Engine.
 *
 * Enforces a strict ArchiMate matrix with internal/external sub-layers.
 * Business, Application, and Technology each split into two sub-rows:
 * - External (top): Services + Interfaces (visible to layer above)
 * - Internal (bottom): Processes, Functions, Actors, Components
 *
 * ╔═══════════════════╦══════════════════╦═════════════════════╦════════════════════╗
 * ║                   ║ Col 0: Passive   ║ Col 1: Behavior     ║ Col 2: Active      ║
 * ╠═══════════════════╬══════════════════╬═════════════════════╬════════════════════╣
 * ║ Row 9: Motivation ║ Meaning, Value   ║ Goal, Principle...  ║ Stakeholder        ║
 * ║ Row 8: Strategy   ║                  ║ Capability, VStr    ║ Resource           ║
 * ╠═══════════════════╬══════════════════╬═════════════════════╬════════════════════╣
 * ║ Row 7: Biz Ext    ║ Contract, Repr   ║ BizService          ║ BizInterface       ║
 * ║ Row 6: Biz Int    ║ BizObject        ║ BizProcess, Func    ║ Actor, Role        ║
 * ╠═══════════════════╬══════════════════╬═════════════════════╬════════════════════╣
 * ║ Row 5: App Ext    ║                  ║ AppService          ║ AppInterface       ║
 * ║ Row 4: App Int    ║ DataObject       ║ AppProcess, Func    ║ AppComponent       ║
 * ╠═══════════════════╬══════════════════╬═════════════════════╬════════════════════╣
 * ║ Row 3: Tech Ext   ║                  ║ TechService         ║ TechInterface      ║
 * ║ Row 2: Tech Int   ║ Artifact         ║ TechProcess, Func   ║ Node, Device       ║
 * ╠═══════════════════╬══════════════════╬═════════════════════╬════════════════════╣
 * ║ Row 1: Physical   ║ Material         ║                     ║ Facility, Equip    ║
 * ║ Row 0: I&M        ║ Deliverable, Gap ║ WorkPackage         ║                    ║
 * ╚═══════════════════╩══════════════════╩═════════════════════╩════════════════════╝
 *
 * The grid is rendered top-to-bottom (Row 9 at top of screen, Row 0 at bottom).
 */
public class ArchiMateGridEngine {

    // ═══════════════════════════════════════════════════════════════════════
    // Configuration Constants
    // ═══════════════════════════════════════════════════════════════════════

    /** Element width — read from Archi preferences (Edit > Preferences > Diagram > Appearance) */
    private final double ELEM_WIDTH;
    /** Element height — read from Archi preferences */
    private final double ELEM_HEIGHT;

    /** Grid size in pixels — read from Archi preferences (Edit > Preferences > Diagram) */
    private final int GRID_SIZE;
    /** Diagram margin width — read from Archi preferences */
    private final int MARGIN;

    /** Vertical spacing between elements stacked within the same cell */
    private final double CELL_PADDING_Y;

    /** Horizontal gap between siblings placed side by side */
    private final double SIBLING_GAP_X;

    /** Horizontal offset per stacked group for diagonal/staircase pattern */
    private final double DIAGONAL_OFFSET_X;

    /** Horizontal gap between aspect columns */
    private final double COLUMN_GAP;
    /** Vertical gap between sub-rows within the same layer */
    private final double ROW_GAP;
    /** Vertical gap between rows of different layers (larger visual separation) */
    private final double LAYER_GAP;

    /** Padding around the entire grid — from Archi margin width preference */
    private final double GRID_MARGIN;

    /** Container padding (top extra for label) */
    private final double CONTAINER_PAD_TOP;
    private final double CONTAINER_PAD_SIDE;
    private final double CONTAINER_PAD_BOTTOM;

    /** Number of grid rows (7 original + 3 sub-row splits) */
    private static final int NUM_ROWS = 10; // Rows 0-9
    /** Number of ArchiMate aspects */
    private static final int NUM_COLS = 3; // Cols 0-2

    // ═══════════════════════════════════════════════════════════════════════
    // Internal Data Structures
    // ═══════════════════════════════════════════════════════════════════════

    /** Grid cell: accumulates leaf elements for a (row, col) position */
    private static class GridCell {
        final List<IDiagramModelObject> elements = new ArrayList<>();
    }

    /**
     * A group of sibling elements that share the same parent container.
     * Siblings within a group are placed SIDE BY SIDE (horizontally).
     * Groups themselves stack VERTICALLY (relationship direction UP).
     */
    private static class SiblingGroup {
        final List<IDiagramModelObject> members = new ArrayList<>();
    }

    /** Computed layout result for a single diagram object */
    public static class LayoutResult {
        public final int x, y, width, height;

        LayoutResult(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /** The 2D grid of cells */
    private final GridCell[][] grid = new GridCell[NUM_ROWS][NUM_COLS];

    /** Maps each diagram object to its computed absolute position */
    private final Map<IDiagramModelObject, LayoutResult> results = new HashMap<>();

    /** Computed column widths (based on widest cell in each column) */
    private final double[] colWidths = new double[NUM_COLS];
    /** Computed row heights (based on tallest cell in each row) */
    private final double[] rowHeights = new double[NUM_ROWS];

    /** Column X origins (left edge of each column) */
    private final double[] colX = new double[NUM_COLS];
    /** Row Y origins (top edge of each row) */
    private final double[] rowY = new double[NUM_ROWS];

    /** Container hierarchy: parent DMO → list of child DMOs */
    private final Map<IDiagramModelObject, List<IDiagramModelObject>> containerChildren = new HashMap<>();

    /** Reverse lookup: child DMO → its parent container DMO */
    private final Map<IDiagramModelObject, IDiagramModelObject> dmoParent = new HashMap<>();

    /** Reverse lookup: DMO → its grid cell coordinates [row, col] */
    private final Map<IDiagramModelObject, int[]> dmoCell = new HashMap<>();

    /** Per-cell sibling groups: [row][col] → list of SiblingGroups */
    private final List<SiblingGroup>[][] cellGroups = new List[NUM_ROWS][NUM_COLS];

    /** Elements that participate in a diagonal staircase (set by applyDiagonalOffsets) */
    private final Set<IDiagramModelObject> staircaseElements = new HashSet<>();

    // ═══════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════

    public ArchiMateGridEngine() {
        var prefs = ArchiPlugin.getInstance().getPreferenceStore();
        ELEM_WIDTH = prefs.getInt(IPreferenceConstants.DEFAULT_ARCHIMATE_FIGURE_WIDTH);
        ELEM_HEIGHT = prefs.getInt(IPreferenceConstants.DEFAULT_ARCHIMATE_FIGURE_HEIGHT);
        GRID_SIZE = Math.max(1, prefs.getInt(IPreferenceConstants.GRID_SIZE));
        MARGIN = prefs.getInt(IPreferenceConstants.MARGIN_WIDTH);

        // Snap spacing constants to grid multiples
        CELL_PADDING_Y = snapToGrid(40);
        SIBLING_GAP_X = snapToGrid(30);
        DIAGONAL_OFFSET_X = snapToGrid(80);
        COLUMN_GAP = snapToGrid(100);
        ROW_GAP = snapToGrid(40);
        LAYER_GAP = snapToGrid(80);
        GRID_MARGIN = snapToGrid(Math.max(40, MARGIN * 8));
        CONTAINER_PAD_TOP = snapToGrid(36);
        CONTAINER_PAD_SIDE = snapToGrid(12);
        CONTAINER_PAD_BOTTOM = snapToGrid(12);
    }

    /** Round a value to the nearest grid multiple */
    private double snapToGrid(double value) {
        return Math.round(value / GRID_SIZE) * GRID_SIZE;
    }

    /** Snap an int position to the nearest grid multiple */
    private int snapInt(double value) {
        return (int) (Math.round(value / GRID_SIZE) * GRID_SIZE);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Compute the layout for an entire ArchiMate diagram.
     *
     * @param diagramModel the Archi diagram model
     * @return map of every diagram object → its computed absolute bounds
     */
    public Map<IDiagramModelObject, LayoutResult> computeLayout(IArchimateDiagramModel diagramModel) {
        // Initialize grid
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                grid[r][c] = new GridCell();
            }
        }

        // Phase 1: CLASSIFY — walk the diagram tree, assign leaf elements to cells
        for (IDiagramModelObject dmo : diagramModel.getChildren()) {
            classifyElement(dmo);
        }

        // Phase 1.5: SORT — topologically order elements within cells by relationships
        sortCellElements();

        // Phase 1.75: GROUP — group siblings for side-by-side placement
        groupCellBySiblings();

        // Phase 2: SIZE — compute cell, column, and row dimensions
        computeGridDimensions();

        // Phase 3: PLACE — compute absolute (x, y) for every leaf element
        placeLeafElements();

        // Phase 3.5: ALIGN — align elements with cross-column connections
        alignCrossColumnElements();

        // Phase 3.75: DIAGONAL STAIRCASE — horizontally shift elements to prevent overlap
        applyDiagonalOffsets();

        // Phase 3.76: RE-ALIGN — the staircase may enforce minimum Y spacing,
        // invalidating the first alignment. Re-align cross-column elements to
        // the corrected staircase positions.
        alignCrossColumnElements();

        // Phase 3.8: STRETCH WIDTH — stretch single elements to match connected sibling groups
        stretchToMatchSiblingGroups();

        // Phase 3.85: CENTER — center single elements over connected elements in same column
        centerOverConnectedElements();

        // Phase 3.9: STRETCH HEIGHT — stretch elements to span connected elements across columns
        stretchHeightAcrossColumns();

        // Phase 3.95: SNAP TO GRID — align all positions to the diagram grid
        for (Map.Entry<IDiagramModelObject, LayoutResult> entry : results.entrySet()) {
            LayoutResult r = entry.getValue();
            entry.setValue(new LayoutResult(
                    snapInt(r.x), snapInt(r.y),
                    snapInt(r.width), snapInt(r.height)));
        }

        // Phase 4: WRAP — compute container bounds to wrap their children
        for (IDiagramModelObject dmo : diagramModel.getChildren()) {
            wrapContainers(dmo);
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 1: CLASSIFY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Recursively classify elements into grid cells.
     * Containers are tracked but not placed on the grid themselves.
     * Only leaf ArchiMate elements are placed into cells.
     */
    private void classifyElement(IDiagramModelObject dmo) {
        boolean hasChildren = dmo instanceof IDiagramModelContainer
                && !((IDiagramModelContainer) dmo).getChildren().isEmpty();

        if (hasChildren) {
            // Track container hierarchy for Phase 4 (WRAP)
            IDiagramModelContainer container = (IDiagramModelContainer) dmo;
            List<IDiagramModelObject> children = new ArrayList<>();
            for (IDiagramModelObject child : container.getChildren()) {
                children.add(child);
                classifyElement(child);
                // Build reverse lookup: child → parent
                dmoParent.put(child, dmo);
            }
            containerChildren.put(dmo, children);
        } else {
            // Leaf element: classify into grid cell
            if (dmo instanceof IDiagramModelArchimateObject) {
                IArchimateElement element = ((IDiagramModelArchimateObject) dmo).getArchimateElement();
                if (element != null) {
                    int row = getLayerRow(element);
                    int col = getAspectColumn(element);
                    grid[row][col].elements.add(dmo);
                    dmoCell.put(dmo, new int[] { row, col });
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 1.5: SORT (Topological order within cells)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sort elements within each grid cell by relationship direction.
     *
     * Rule: If element A is the SOURCE of a relationship to element B,
     * B is placed ABOVE A (arrows flow UPWARD visually).
     *
     * This produces the correct ArchiMate visual ordering:
     * - Process above Function (composition arrow points up)
     * - Role above Actor (assignment arrow points up)
     *
     * Implementation: Builds a REVERSED adjacency graph (target → source),
     * then topologically sorts so targets appear first (top of cell).
     *
     * Uses Kahn's algorithm (BFS topological sort) with graceful
     * fallback for cycles (preserves original order for tied elements).
     */
    private void sortCellElements() {
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                GridCell cell = grid[r][c];
                if (cell.elements.size() <= 1)
                    continue;

                // Build a set of DMOs in this cell for quick lookup
                Set<IDiagramModelObject> cellSet = new HashSet<>(cell.elements);

                // Build REVERSED adjacency: target → source (targets sort first/top)
                Map<IDiagramModelObject, List<IDiagramModelObject>> adj = new HashMap<>();
                Map<IDiagramModelObject, Integer> inDegree = new HashMap<>();
                for (IDiagramModelObject dmo : cell.elements) {
                    adj.put(dmo, new ArrayList<>());
                    inDegree.put(dmo, 0);
                }

                for (IDiagramModelObject dmo : cell.elements) {
                    for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                        if (!(conn.getTarget() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject target = (IDiagramModelObject) conn.getTarget();
                        if (cellSet.contains(target) && target != dmo) {
                            // REVERSED: edge from target → source
                            adj.get(target).add(dmo);
                            inDegree.put(dmo, inDegree.get(dmo) + 1);
                        }
                    }
                }

                // Kahn's topological sort (BFS)
                Queue<IDiagramModelObject> queue = new LinkedList<>();
                for (IDiagramModelObject dmo : cell.elements) {
                    if (inDegree.get(dmo) == 0) {
                        queue.add(dmo);
                    }
                }

                List<IDiagramModelObject> sorted = new ArrayList<>();
                while (!queue.isEmpty()) {
                    IDiagramModelObject dmo = queue.poll();
                    sorted.add(dmo);
                    for (IDiagramModelObject target : adj.get(dmo)) {
                        int newDegree = inDegree.get(target) - 1;
                        inDegree.put(target, newDegree);
                        if (newDegree == 0) {
                            queue.add(target);
                        }
                    }
                }

                // If there are cycles, append remaining elements in original order
                if (sorted.size() < cell.elements.size()) {
                    for (IDiagramModelObject dmo : cell.elements) {
                        if (!sorted.contains(dmo)) {
                            sorted.add(dmo);
                        }
                    }
                }

                // Replace the cell's element list with the sorted order
                cell.elements.clear();
                cell.elements.addAll(sorted);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 1.75: GROUP (Sibling grouping for side-by-side placement)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Group elements within each cell for side-by-side placement.
     *
     * Only NESTED SIBLINGS (children of the same diagram container)
     * are grouped side by side. Non-nested elements each become their
     * own group and stack vertically, allowing the ALIGN phase to
     * match paired elements across columns for clean connections.
     *
     * Within a group, elements are placed SIDE BY SIDE (horizontally).
     * Groups stack VERTICALLY (preserving the UP relationship direction).
     * Order follows the topological sort from Phase 1.5.
     */
    private void groupCellBySiblings() {
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                List<SiblingGroup> groups = new ArrayList<>();
                cellGroups[r][c] = groups;

                GridCell cell = grid[r][c];
                if (cell.elements.isEmpty())
                    continue;

                // Group by shared diagram parent container
                Map<IDiagramModelObject, SiblingGroup> parentToGroup = new HashMap<>();

                for (IDiagramModelObject dmo : cell.elements) {
                    IDiagramModelObject parent = dmoParent.get(dmo);

                    if (parent != null && parentToGroup.containsKey(parent)) {
                        // Add to existing sibling group
                        parentToGroup.get(parent).members.add(dmo);
                    } else {
                        // Start a new group (single element or first of a parent)
                        SiblingGroup group = new SiblingGroup();
                        group.members.add(dmo);
                        groups.add(group);
                        if (parent != null) {
                            parentToGroup.put(parent, group);
                        }
                    }
                }
            }
        }
    }

    /**
     * Gets the ArchiMate class name for the first element in a group,
     * used to identify clusters of the exact same element type (e.g. ApplicationService).
     */
    private String getArchiMateClassName(SiblingGroup group) {
        if (!group.members.isEmpty()) {
            IDiagramModelObject dmo = group.members.get(0);
            if (dmo instanceof com.archimatetool.model.IDiagramModelArchimateObject) {
                com.archimatetool.model.IArchimateElement element = 
                    ((com.archimatetool.model.IDiagramModelArchimateObject) dmo).getArchimateElement();
                if (element != null) {
                    return element.eClass().getName();
                }
            }
        }
        return "Unknown";
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 2: SIZE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Compute the width/height of each cell, then derive column widths
     * and row heights from the maximum dimensions across cells.
     *
     * Within a SiblingGroup, elements are placed SIDE BY SIDE:
     *   groupWidth  = sum(elem widths) + (n-1) * SIBLING_GAP_X
     *   groupHeight = max(elem heights)
     *
     * Groups stack VERTICALLY (relationship direction UP):
     *   cellWidth  = max(groupWidths)
     *   cellHeight = sum(groupHeights) + inter-group CELL_PADDING_Y
     */
    private void computeGridDimensions() {
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null || groups.isEmpty())
                    continue;

                double cellWidth = 0;
                double cellHeight = 0;

                // Count how many groups exist for each ArchiMate type in this cell
                Map<String, Integer> typeCounts = new HashMap<>();
                for (SiblingGroup group : groups) {
                    String type = getArchiMateClassName(group);
                    typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
                }

                for (int g = 0; g < groups.size(); g++) {
                    SiblingGroup group = groups.get(g);
                    double groupWidth = 0;
                    double groupHeight = 0;

                    for (int i = 0; i < group.members.size(); i++) {
                        double w = ELEM_WIDTH;
                        double h = ELEM_HEIGHT;
                        groupWidth += w;
                        if (i > 0)
                            groupWidth += SIBLING_GAP_X;
                        groupHeight = Math.max(groupHeight, h);
                    }

                    // Only elements of the SAME type will staircase. Multiply DIAGONAL_OFFSET_X
                    // by solely the number of same-type groups.
                    int sameTypeCount = typeCounts.get(getArchiMateClassName(group));
                    double effectiveWidth = groupWidth + (sameTypeCount - 1) * DIAGONAL_OFFSET_X;
                    
                    cellWidth = Math.max(cellWidth, effectiveWidth);
                    cellHeight += groupHeight;
                    if (g > 0)
                        cellHeight += CELL_PADDING_Y;
                }

                colWidths[c] = Math.max(colWidths[c], cellWidth);
                rowHeights[r] = Math.max(rowHeights[r], cellHeight);
            }
        }

        // Ensure minimum column widths even if empty
        for (int c = 0; c < NUM_COLS; c++) {
            colWidths[c] = Math.max(colWidths[c], ELEM_WIDTH);
        }

        // Compute column X origins (left to right)
        colX[0] = GRID_MARGIN;
        for (int c = 1; c < NUM_COLS; c++) {
            colX[c] = colX[c - 1] + colWidths[c - 1] + COLUMN_GAP;
        }

        // Compute row Y origins (top to bottom: Row 9 at top, Row 0 at bottom)
        // Uses ROW_GAP between sub-rows of the same layer, and the larger
        // LAYER_GAP between rows of different layers. Empty rows are skipped.
        rowY[NUM_ROWS - 1] = GRID_MARGIN; // Row 9 (Motivation) starts at the top
        for (int r = NUM_ROWS - 2; r >= 0; r--) {
            // Find the nearest non-empty row above this one
            int aboveRow = -1;
            for (int a = r + 1; a < NUM_ROWS; a++) {
                if (rowHeights[a] > 0) {
                    aboveRow = a;
                    break;
                }
            }

            if (rowHeights[r] == 0) {
                // This row is empty: place at the bottom of the row above
                rowY[r] = (aboveRow >= 0) ? rowY[aboveRow] + rowHeights[aboveRow] : GRID_MARGIN;
            } else if (aboveRow < 0) {
                // No non-empty row above: start at margin
                rowY[r] = GRID_MARGIN;
            } else {
                // Both non-empty: use ROW_GAP if same layer, LAYER_GAP if different
                double gap = (getLayerGroup(r) == getLayerGroup(aboveRow)) ? ROW_GAP : LAYER_GAP;
                rowY[r] = rowY[aboveRow] + rowHeights[aboveRow] + gap;
            }
        }
    }

    /**
     * Maps a grid row to its parent layer group.
     * Sub-rows of the same layer share the same group number.
     *
     * Group 6: Motivation (Row 9)
     * Group 5: Strategy (Row 8)
     * Group 4: Business (Row 7 External, Row 6 Internal)
     * Group 3: Application(Row 5 External, Row 4 Internal)
     * Group 2: Technology (Row 3 External, Row 2 Internal)
     * Group 1: Physical (Row 1)
     * Group 0: I&M (Row 0)
     */
    private static int getLayerGroup(int row) {
        switch (row) {
            case 9:
                return 6; // Motivation
            case 8:
                return 5; // Strategy
            case 7:
            case 6:
                return 4; // Business
            case 5:
            case 4:
                return 3; // Application
            case 3:
            case 2:
                return 2; // Technology
            case 1:
                return 1; // Physical
            case 0:
                return 0; // I&M
            default:
                return -1;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3: PLACE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Compute absolute (x, y) positions for every leaf element.
     *
     * SiblingGroups within a cell stack VERTICALLY (arrows flow UP).
     * Within each SiblingGroup:
     *   - Single element: centered horizontally in the column (unchanged)
     *   - Multiple elements: placed SIDE BY SIDE, centered as a row
     */
    private void placeLeafElements() {
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null || groups.isEmpty())
                    continue;

                // Compute total height of all groups in this cell
                double totalHeight = 0;
                for (int g = 0; g < groups.size(); g++) {
                    SiblingGroup group = groups.get(g);
                    double groupHeight = 0;
                    for (IDiagramModelObject dmo : group.members) {
                        groupHeight = Math.max(groupHeight, ELEM_HEIGHT);
                    }
                    totalHeight += groupHeight;
                    if (g > 0)
                        totalHeight += CELL_PADDING_Y;
                }

                // Center the stack vertically within the row
                double curY = rowY[r] + (rowHeights[r] - totalHeight) / 2.0;

                for (SiblingGroup group : groups) {
                    // Compute group dimensions
                    double groupWidth = 0;
                    double groupHeight = 0;
                    for (int i = 0; i < group.members.size(); i++) {
                        groupWidth += ELEM_WIDTH;
                        if (i > 0)
                            groupWidth += SIBLING_GAP_X;
                        groupHeight = Math.max(groupHeight, ELEM_HEIGHT);
                    }

                    // Center the group horizontally within the column
                    double groupStartX = colX[c] + (colWidths[c] - groupWidth) / 2.0;
                    double curX = groupStartX;

                    for (IDiagramModelObject dmo : group.members) {
                        double w = ELEM_WIDTH;
                        double h = ELEM_HEIGHT;

                        // Center each element vertically within the group row
                        double y = curY + (groupHeight - h) / 2.0;

                        results.put(dmo, new LayoutResult(
                                (int) Math.round(curX),
                                (int) Math.round(y),
                                (int) Math.round(w),
                                (int) Math.round(h)));

                        curX += w + SIBLING_GAP_X;
                    }

                    curY += groupHeight + CELL_PADDING_Y;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3.5: ALIGN (Cross-column vertical alignment)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Align elements vertically with their cross-column connection partners.
     *
     * If a cell has fewer elements than the tallest cell in its row,
     * elements would otherwise be centered. Instead, each element is
     * shifted to align with the Y-center of its connected element(s)
     * in adjacent columns of the same row.
     */
    private void alignCrossColumnElements() {
        for (int r = 0; r < NUM_ROWS; r++) {
            // Find the maximum element count in this row
            int maxCount = 0;
            for (int c = 0; c < NUM_COLS; c++) {
                maxCount = Math.max(maxCount, grid[r][c].elements.size());
            }

            // Find the anchor column (most elements) for this row
            int anchorCol = 0;
            for (int c = 1; c < NUM_COLS; c++) {
                if (grid[r][c].elements.size() > grid[r][anchorCol].elements.size()) {
                    anchorCol = c;
                }
            }

            // Process columns outward from the anchor so that closer columns
            // are aligned first, and farther columns can reference their
            // already-corrected positions (e.g. col2 → col1 → col0).
            for (int dist = 1; dist < NUM_COLS; dist++) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    int c = anchorCol + dir * dist;
                    if (c < 0 || c >= NUM_COLS) continue;

                GridCell cell = grid[r][c];
                if (cell.elements.isEmpty() || cell.elements.size() >= maxCount)
                    continue;

                for (IDiagramModelObject dmo : cell.elements) {
                    // Skip staircase elements — they keep the Y set by the staircase phase
                    if (staircaseElements.contains(dmo))
                        continue;

                    LayoutResult myResult = results.get(dmo);
                    if (myResult == null)
                        continue;

                    // Find cross-column connection partners in the same row
                    double totalPartnerY = 0;
                    int partnerCount = 0;

                    // Check outgoing connections
                    for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                        if (!(conn.getTarget() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject partner = (IDiagramModelObject) conn.getTarget();
                        int[] partnerPos = dmoCell.get(partner);
                        if (partnerPos != null && partnerPos[0] == r && partnerPos[1] != c
                                && Math.abs(partnerPos[1] - anchorCol) < dist) {
                            LayoutResult partnerResult = results.get(partner);
                            if (partnerResult != null) {
                                totalPartnerY += partnerResult.y + partnerResult.height / 2.0;
                                partnerCount++;
                            }
                        }
                    }

                    // Check incoming connections
                    for (IDiagramModelConnection conn : dmo.getTargetConnections()) {
                        if (!(conn.getSource() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject partner = (IDiagramModelObject) conn.getSource();
                        int[] partnerPos = dmoCell.get(partner);
                        if (partnerPos != null && partnerPos[0] == r && partnerPos[1] != c
                                && Math.abs(partnerPos[1] - anchorCol) < dist) {
                            LayoutResult partnerResult = results.get(partner);
                            if (partnerResult != null) {
                                totalPartnerY += partnerResult.y + partnerResult.height / 2.0;
                                partnerCount++;
                            }
                        }
                    }

                    // Align to partner's vertical center
                    if (partnerCount > 0) {
                        double avgPartnerCenterY = totalPartnerY / partnerCount;
                        int newY = (int) Math.round(avgPartnerCenterY - myResult.height / 2.0);
                        results.put(dmo, new LayoutResult(
                                myResult.x, newY, myResult.width, myResult.height));
                    }
                }
              }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3.75: DIAGONAL STAIRCASE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Applies the horizontal staircase offset based on the final, aligned Y coordinates.
     * This ensures all columns staircase in the exact same direction (top-left to bottom-right),
     * preventing criss-crossing lines and keeping parent single-elements perfectly centered relative
     * to the staircase.
     *
     * After applying diagonal offsets, stretches all single elements in the same column
     * to match the staircase width, so parent/child elements align visually with their siblings.
     */
    private void applyDiagonalOffsets() {
        // Track max staircase width per column
        double[] colStaircaseWidth = new double[NUM_COLS];
        staircaseElements.clear();

        for (int r = 0; r < NUM_ROWS; r++) {
            // Find the anchor column (most elements) for this row.
            // Only the anchor column gets a staircase; other columns
            // follow through cross-column alignment.
            int anchorCol = 0;
            for (int cc = 1; cc < NUM_COLS; cc++) {
                if (grid[r][cc].elements.size() > grid[r][anchorCol].elements.size()) {
                    anchorCol = cc;
                }
            }

            for (int c = 0; c < NUM_COLS; c++) {

                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null || groups.size() <= 1)
                    continue;

                // Group the SiblingGroups by their exact ArchiMate class.
                Map<String, List<SiblingGroup>> groupsByType = new HashMap<>();
                for (SiblingGroup group : groups) {
                    String type = getArchiMateClassName(group);
                    groupsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(group);
                }

                double columnCenterX = colX[c] + colWidths[c] / 2.0;

                for (List<SiblingGroup> typeGroups : groupsByType.values()) {
                    if (typeGroups.size() <= 1) {
                        continue;
                    }

                    // Sort groups of this exact type by their physical Y coordinate
                    List<SiblingGroup> sortedGroups = new ArrayList<>(typeGroups);
                    sortedGroups.sort((g1, g2) -> {
                        double y1 = results.get(g1.members.get(0)).y;
                        double y2 = results.get(g2.members.get(0)).y;
                        return Double.compare(y1, y2);
                    });

                    // Identify spanning groups: single-member groups connected to 2+
                    // other groups in this staircase. These are parent/umbrella elements
                    // that should stretch across the staircase rather than participate in it.
                    Set<SiblingGroup> spanningGroupSet = new HashSet<>();
                    Set<IDiagramModelObject> allMembers = new HashSet<>();
                    for (SiblingGroup g : sortedGroups) {
                        allMembers.addAll(g.members);
                    }

                    for (SiblingGroup candidate : sortedGroups) {
                        if (candidate.members.size() != 1) continue;
                        IDiagramModelObject dmo = candidate.members.get(0);
                        int connectedPeers = 0;

                        for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                            if (conn.getTarget() instanceof IDiagramModelObject
                                    && allMembers.contains(conn.getTarget())
                                    && conn.getTarget() != dmo) {
                                connectedPeers++;
                            }
                        }
                        for (IDiagramModelConnection conn : dmo.getTargetConnections()) {
                            if (conn.getSource() instanceof IDiagramModelObject
                                    && allMembers.contains(conn.getSource())
                                    && conn.getSource() != dmo) {
                                connectedPeers++;
                            }
                        }

                        if (connectedPeers >= 2) {
                            spanningGroupSet.add(candidate);
                        }
                    }

                    // Filter out spanning groups, keeping only staircase participants
                    List<SiblingGroup> activeGroups = new ArrayList<>();
                    for (SiblingGroup g : sortedGroups) {
                        if (!spanningGroupSet.contains(g)) {
                            activeGroups.add(g);
                        }
                    }

                    // Need at least 2 groups for a staircase
                    if (activeGroups.size() <= 1) {
                        continue;
                    }

                    // Enforce minimum vertical spacing between staircase elements,
                    // but ONLY in the anchor column. Non-anchor columns get their Y
                    // from cross-column alignment, so Y enforcement would conflict.
                    if (c == anchorCol) {
                    double minYSep = ELEM_HEIGHT + CELL_PADDING_Y;
                    for (int i = 1; i < activeGroups.size(); i++) {
                        LayoutResult prevRes = results.get(activeGroups.get(i - 1).members.get(0));
                        LayoutResult curRes = results.get(activeGroups.get(i).members.get(0));
                        if (curRes.y - prevRes.y < minYSep) {
                            int newY = (int) Math.round(prevRes.y + minYSep);
                            for (IDiagramModelObject dmo : activeGroups.get(i).members) {
                                LayoutResult res = results.get(dmo);
                                results.put(dmo, new LayoutResult(res.x, newY, res.width, res.height));
                            }
                        }
                    }
                    } // end Y enforcement (anchor only)

                    int n = activeGroups.size();
                    
                    // 1. Calculate the exact width of each group
                    double[] groupWidths = new double[n];
                    for (int i = 0; i < n; i++) {
                        SiblingGroup group = activeGroups.get(i);
                        double w = 0;
                        for (int j = 0; j < group.members.size(); j++) {
                            w += ELEM_WIDTH;
                            if (j > 0) w += SIBLING_GAP_X;
                        }
                        groupWidths[i] = w;
                    }

                    // 2. Find the bounding box of the entire staircase structure relative to X=0
                    double minStaircaseX = Double.MAX_VALUE;
                    double maxStaircaseX = Double.MIN_VALUE;

                    for (int i = 0; i < n; i++) {
                        double centerOffset = (i - (n - 1) / 2.0) * DIAGONAL_OFFSET_X;
                        double startX = centerOffset - groupWidths[i] / 2.0;
                        double endX = centerOffset + groupWidths[i] / 2.0;
                        minStaircaseX = Math.min(minStaircaseX, startX);
                        maxStaircaseX = Math.max(maxStaircaseX, endX);
                    }

                    double staircaseWidth = maxStaircaseX - minStaircaseX;
                    colStaircaseWidth[c] = Math.max(colStaircaseWidth[c], staircaseWidth);

                    // 3. Apply the shift to perfectly center the entire staircase box in the column
                    for (int i = 0; i < n; i++) {
                        SiblingGroup group = activeGroups.get(i);
                        
                        double centerOffset = (i - (n - 1) / 2.0) * DIAGONAL_OFFSET_X;
                        double localStartX = centerOffset - groupWidths[i] / 2.0;
                        double targetStartX = columnCenterX - (staircaseWidth / 2.0) + (localStartX - minStaircaseX);

                        double curX = targetStartX;
                        for (IDiagramModelObject dmo : group.members) {
                            LayoutResult res = results.get(dmo);
                            
                            results.put(dmo, new LayoutResult(
                                    (int) Math.round(curX), 
                                    res.y, 
                                    (int) Math.round(ELEM_WIDTH), 
                                    res.height));
                                    
                            curX += ELEM_WIDTH + SIBLING_GAP_X;
                            staircaseElements.add(dmo);
                        }
                    }

                    // 4. Stretch spanning elements to the full staircase width
                    for (SiblingGroup sg : spanningGroupSet) {
                        IDiagramModelObject dmo = sg.members.get(0);
                        LayoutResult res = results.get(dmo);
                        if (res != null) {
                            int newWidth = (int) Math.round(staircaseWidth);
                            int newX = (int) Math.round(columnCenterX - staircaseWidth / 2.0);
                            results.put(dmo, new LayoutResult(newX, res.y, newWidth, res.height));
                            staircaseElements.add(dmo);
                        }
                    }
                }
            }
        }

        // 4. Stretch single elements in each column that have connections to staircase elements
        for (int c = 0; c < NUM_COLS; c++) {
            if (colStaircaseWidth[c] <= 0)
                continue;
            
            double columnCenterX = colX[c] + colWidths[c] / 2.0;
            int newWidth = (int) Math.round(colStaircaseWidth[c]);
            int newX = (int) Math.round(columnCenterX - colStaircaseWidth[c] / 2.0);

            for (int r = 0; r < NUM_ROWS; r++) {
                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null)
                    continue;

                for (SiblingGroup group : groups) {
                    if (group.members.size() != 1)
                        continue;

                    IDiagramModelObject dmo = group.members.get(0);
                    if (staircaseElements.contains(dmo))
                        continue;

                    // Count connections to staircase elements — require 2+ to stretch
                    // (single connections should not cause full-width stretching)
                    int staircaseConnCount = 0;
                    for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                        if (conn.getTarget() instanceof IDiagramModelObject
                                && staircaseElements.contains(conn.getTarget())) {
                            staircaseConnCount++;
                        }
                    }
                    for (IDiagramModelConnection conn : dmo.getTargetConnections()) {
                        if (conn.getSource() instanceof IDiagramModelObject
                                && staircaseElements.contains(conn.getSource())) {
                            staircaseConnCount++;
                        }
                    }

                    if (staircaseConnCount >= 2) {
                        LayoutResult res = results.get(dmo);
                        results.put(dmo, new LayoutResult(newX, res.y, newWidth, res.height));
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3.8: STRETCH (match connected sibling group widths)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Stretches single elements to match the combined width of the sibling
     * groups they connect to.
     *
     * For example, if a Business Actor (single element) connects to two
     * Application Interfaces placed side-by-side, the Actor is stretched
     * to span the same width as the combined interfaces.
     *
     * Only affects single-element groups connecting to multi-member groups.
     * Does not shrink elements already wider (e.g. from staircase stretch).
     */
    private void stretchToMatchSiblingGroups() {
        // 1. Build reverse lookup: element → its SiblingGroup
        Map<IDiagramModelObject, SiblingGroup> elementToGroup = new HashMap<>();
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                if (cellGroups[r][c] != null) {
                    for (SiblingGroup g : cellGroups[r][c]) {
                        for (IDiagramModelObject m : g.members) {
                            elementToGroup.put(m, g);
                        }
                    }
                }
            }
        }

        // 2. For each single-element group, find widest connected sibling group
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null)
                    continue;

                for (SiblingGroup group : groups) {
                    if (group.members.size() != 1)
                        continue;

                    IDiagramModelObject dmo = group.members.get(0);
                    double maxConnectedGroupWidth = 0;

                    // Check outgoing connections
                    for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                        if (!(conn.getTarget() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject target = (IDiagramModelObject) conn.getTarget();
                        SiblingGroup targetGroup = elementToGroup.get(target);
                        if (targetGroup != null && targetGroup.members.size() > 1) {
                            maxConnectedGroupWidth = Math.max(maxConnectedGroupWidth,
                                    computeSiblingGroupWidth(targetGroup));
                        }
                    }

                    // Check incoming connections
                    for (IDiagramModelConnection conn : dmo.getTargetConnections()) {
                        if (!(conn.getSource() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject source = (IDiagramModelObject) conn.getSource();
                        SiblingGroup sourceGroup = elementToGroup.get(source);
                        if (sourceGroup != null && sourceGroup.members.size() > 1) {
                            maxConnectedGroupWidth = Math.max(maxConnectedGroupWidth,
                                    computeSiblingGroupWidth(sourceGroup));
                        }
                    }

                    // 3. Stretch if a wider sibling group was found
                    if (maxConnectedGroupWidth > ELEM_WIDTH) {
                        LayoutResult res = results.get(dmo);
                        if (res != null && maxConnectedGroupWidth > res.width) {
                            int newWidth = (int) Math.round(maxConnectedGroupWidth);
                            int newX = (int) Math.round(res.x + res.width / 2.0 - newWidth / 2.0);
                            results.put(dmo, new LayoutResult(newX, res.y, newWidth, res.height));
                        }
                    }
                }
            }
        }
    }

    /**
     * Computes the total rendered width of a sibling group (elements side by side).
     */
    private double computeSiblingGroupWidth(SiblingGroup group) {
        double width = 0;
        for (int i = 0; i < group.members.size(); i++) {
            width += ELEM_WIDTH;
            if (i > 0)
                width += SIBLING_GAP_X;
        }
        return width;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3.85: CENTER (align X over connected elements in same column)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Centers single elements horizontally over their connected elements
     * in adjacent rows of the same column.
     *
     * For example, if Application Interface A (row 5, col 2) connects to
     * Application Component AB (row 4, col 2), the Interface is repositioned
     * to center over the Component's X position rather than the column center.
     */
    private void centerOverConnectedElements() {
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null)
                    continue;

                for (SiblingGroup group : groups) {
                    if (group.members.size() != 1)
                        continue;

                    IDiagramModelObject dmo = group.members.get(0);
                    if (staircaseElements.contains(dmo))
                        continue;

                    LayoutResult myRes = results.get(dmo);
                    if (myRes == null)
                        continue;

                    // Find connected elements in the SAME column but DIFFERENT rows
                    double totalCenterX = 0;
                    double minX = Double.MAX_VALUE;
                    double maxX = Double.MIN_VALUE;
                    int partnerCount = 0;

                    for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                        if (!(conn.getTarget() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject partner = (IDiagramModelObject) conn.getTarget();
                        int[] partnerPos = dmoCell.get(partner);
                        if (partnerPos != null && partnerPos[1] == c && partnerPos[0] != r) {
                            LayoutResult partnerRes = results.get(partner);
                            if (partnerRes != null) {
                                double cx = partnerRes.x + partnerRes.width / 2.0;
                                totalCenterX += cx;
                                minX = Math.min(minX, partnerRes.x);
                                maxX = Math.max(maxX, partnerRes.x + partnerRes.width);
                                partnerCount++;
                            }
                        }
                    }
                    for (IDiagramModelConnection conn : dmo.getTargetConnections()) {
                        if (!(conn.getSource() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject partner = (IDiagramModelObject) conn.getSource();
                        int[] partnerPos = dmoCell.get(partner);
                        if (partnerPos != null && partnerPos[1] == c && partnerPos[0] != r) {
                            LayoutResult partnerRes = results.get(partner);
                            if (partnerRes != null) {
                                double cx = partnerRes.x + partnerRes.width / 2.0;
                                totalCenterX += cx;
                                minX = Math.min(minX, partnerRes.x);
                                maxX = Math.max(maxX, partnerRes.x + partnerRes.width);
                                partnerCount++;
                            }
                        }
                    }

                    if (partnerCount > 0) {
                        double avgCenterX = totalCenterX / partnerCount;
                        int newX = (int) Math.round(avgCenterX - myRes.width / 2.0);
                        results.put(dmo, new LayoutResult(newX, myRes.y, myRes.width, myRes.height));
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3.9: STRETCH HEIGHT (span connected elements across columns)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Stretches the height of elements that connect to 2+ elements across
     * columns at different Y positions.
     *
     * For example, Application Process connects to both Function A and
     * Function B in an adjacent column. It stretches vertically to span
     * from Function A's top to Function B's bottom.
     */
    private void stretchHeightAcrossColumns() {
        for (int r = 0; r < NUM_ROWS; r++) {
            for (int c = 0; c < NUM_COLS; c++) {
                List<SiblingGroup> groups = cellGroups[r][c];
                if (groups == null)
                    continue;

                for (SiblingGroup group : groups) {
                    if (group.members.size() != 1)
                        continue;

                    IDiagramModelObject dmo = group.members.get(0);
                    LayoutResult myRes = results.get(dmo);
                    if (myRes == null)
                        continue;

                    // Find cross-column partners in the same row
                    double minPartnerY = Double.MAX_VALUE;
                    double maxPartnerBottom = Double.MIN_VALUE;
                    int partnerCount = 0;

                    for (IDiagramModelConnection conn : dmo.getSourceConnections()) {
                        if (!(conn.getTarget() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject partner = (IDiagramModelObject) conn.getTarget();
                        int[] partnerPos = dmoCell.get(partner);
                        if (partnerPos != null && partnerPos[0] == r && partnerPos[1] != c) {
                            LayoutResult partnerRes = results.get(partner);
                            if (partnerRes != null) {
                                minPartnerY = Math.min(minPartnerY, partnerRes.y);
                                maxPartnerBottom = Math.max(maxPartnerBottom,
                                        partnerRes.y + partnerRes.height);
                                partnerCount++;
                            }
                        }
                    }
                    for (IDiagramModelConnection conn : dmo.getTargetConnections()) {
                        if (!(conn.getSource() instanceof IDiagramModelObject))
                            continue;
                        IDiagramModelObject partner = (IDiagramModelObject) conn.getSource();
                        int[] partnerPos = dmoCell.get(partner);
                        if (partnerPos != null && partnerPos[0] == r && partnerPos[1] != c) {
                            LayoutResult partnerRes = results.get(partner);
                            if (partnerRes != null) {
                                minPartnerY = Math.min(minPartnerY, partnerRes.y);
                                maxPartnerBottom = Math.max(maxPartnerBottom,
                                        partnerRes.y + partnerRes.height);
                                partnerCount++;
                            }
                        }
                    }

                    // Only stretch if partners span more than one element height
                    if (partnerCount >= 2 && maxPartnerBottom - minPartnerY > ELEM_HEIGHT) {
                        int newY = (int) Math.round(minPartnerY);
                        int newHeight = (int) Math.round(maxPartnerBottom - minPartnerY);
                        results.put(dmo, new LayoutResult(myRes.x, newY, myRes.width, newHeight));
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 4: WRAP
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Compute container bounds to elastically wrap around their children.
     * Containers don't impose grid positions — they just grow to enclose
     * all their children with padding.
     */
    private void wrapContainers(IDiagramModelObject dmo) {
        List<IDiagramModelObject> children = containerChildren.get(dmo);
        if (children == null)
            return;

        // First, recursively wrap any nested containers
        for (IDiagramModelObject child : children) {
            wrapContainers(child);
        }

        // Find bounding box of all children
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

        for (IDiagramModelObject child : children) {
            LayoutResult childResult = results.get(child);
            if (childResult != null) {
                minX = Math.min(minX, childResult.x);
                minY = Math.min(minY, childResult.y);
                maxX = Math.max(maxX, childResult.x + childResult.width);
                maxY = Math.max(maxY, childResult.y + childResult.height);
            }
        }

        if (minX == Double.MAX_VALUE)
            return; // No children with results

        // Expand to include padding
        int containerX = (int) Math.round(minX - CONTAINER_PAD_SIDE);
        int containerY = (int) Math.round(minY - CONTAINER_PAD_TOP);
        int containerW = (int) Math.round((maxX - minX) + 2 * CONTAINER_PAD_SIDE);
        int containerH = (int) Math.round((maxY - minY) + CONTAINER_PAD_TOP + CONTAINER_PAD_BOTTOM);

        results.put(dmo, new LayoutResult(containerX, containerY, containerW, containerH));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Layer Classification (Vertical Y-Axis) with Sub-rows
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Classify an element into its ArchiMate layer sub-row.
     *
     * Row 9 (top): Motivation
     * Row 8: Strategy
     * Row 7: Business External (Service, Interface, Contract, Representation)
     * Row 6: Business Internal (Process, Function, Actor, Role, Object)
     * Row 5: Application External (Service, Interface)
     * Row 4: Application Internal (Process, Function, Component, DataObject)
     * Row 3: Technology External (Service, Interface)
     * Row 2: Technology Internal (Process, Function, Node, Device, Artifact)
     * Row 1: Physical
     * Row 0 (bot): Implementation & Migration
     */
    private int getLayerRow(IArchimateElement element) {
        // Strategy: check first (Capability, ValueStream, CourseOfAction, Resource)
        if (element instanceof IStrategyElement)
            return 8;

        // Implementation & Migration: check before Passive/Behavior
        if (element instanceof IImplementationMigrationElement)
            return 0;

        // Physical: check before Technology
        if (element instanceof IPhysicalElement)
            return 1;

        // Motivation
        if (element instanceof IMotivationElement)
            return 9;

        // Business: split into External (7) / Internal (6)
        if (element instanceof IBusinessElement)
            return isExternalElement(element) ? 7 : 6;

        // Application: split into External (5) / Internal (4)
        if (element instanceof IApplicationElement)
            return isExternalElement(element) ? 5 : 4;

        // Technology: split into External (3) / Internal (2)
        if (element instanceof ITechnologyElement)
            return isExternalElement(element) ? 3 : 2;

        return 6; // Fallback: Business Internal
    }

    /**
     * Determines if an element is "external" (visible to the layer above).
     *
     * External elements are:
     * - Services (Business, Application, Technology)
     * - Interfaces (Business, Application, Technology)
     * - Contract, Representation (Business passive, exposed via services)
     *
     * All other elements are "internal" (implementation detail).
     */
    private boolean isExternalElement(IArchimateElement element) {
        // Services (all are IBehaviorElement subclasses)
        if (element instanceof IBusinessService)
            return true;
        if (element instanceof IApplicationService)
            return true;
        if (element instanceof ITechnologyService)
            return true;

        // Interfaces (all are IActiveStructureElement subclasses)
        if (element instanceof IBusinessInterface)
            return true;
        if (element instanceof IApplicationInterface)
            return true;
        if (element instanceof ITechnologyInterface)
            return true;

        // Business passive external: Contract, Representation
        if (element instanceof IContract)
            return true;
        if (element instanceof IRepresentation)
            return true;

        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Aspect Classification (Horizontal X-Axis)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Classify an element into its ArchiMate aspect column.
     *
     * Col 0 (left): Passive Structure
     * Col 1 (center): Behavior
     * Col 2 (right): Active Structure
     *
     * Aspect interfaces are the most specific classifiers and are
     * checked first to handle dual-inheritance correctly.
     */
    private int getAspectColumn(IArchimateElement element) {
        // ── Primary Aspect Interfaces ───────────────────────────────

        // Active Structure → Right
        // BusinessActor, BusinessRole, BusinessInterface, BusinessCollaboration,
        // ApplicationComponent, ApplicationCollaboration, ApplicationInterface,
        // Node, Device, SystemSoftware, CommunicationNetwork, Path,
        // TechnologyInterface, TechnologyCollaboration,
        // Facility, Equipment, DistributionNetwork,
        // Stakeholder
        if (element instanceof IActiveStructureElement)
            return 2;

        // Passive Structure → Left
        // BusinessObject, Contract, Representation,
        // DataObject, TechnologyObject/Artifact, Material,
        // Deliverable, Gap
        if (element instanceof IPassiveStructureElement)
            return 0;

        // Behavior → Center
        // BusinessProcess, BusinessFunction, BusinessService, BusinessEvent,
        // BusinessInteraction, ApplicationFunction, ApplicationProcess,
        // ApplicationService, ApplicationEvent, ApplicationInteraction,
        // TechnologyProcess, TechnologyFunction, TechnologyService,
        // TechnologyEvent, TechnologyInteraction,
        // WorkPackage, CourseOfAction, Capability, ValueStream
        if (element instanceof IBehaviorElement)
            return 1;

        // ── Motivation elements (no aspect interface) ───────────────

        // Meaning, Value → Passive (left)
        if (element instanceof IMeaning || element instanceof IValue)
            return 0;

        // Goal, Requirement, Principle, Constraint, Driver, Assessment, Outcome →
        // Behavior (center)
        if (element instanceof IGoal || element instanceof IRequirement
                || element instanceof IPrinciple || element instanceof IConstraint
                || element instanceof IDriver || element instanceof IAssessment
                || element instanceof IOutcome)
            return 1;

        // ── Composite / Other ───────────────────────────────────────

        // Product, Plateau, Grouping, Location → Center (neutral)
        if (element instanceof ICompositeElement)
            return 1;

        // Resource (Strategy, structural) → Active (right)
        if (element instanceof IResource)
            return 2;

        return 1; // Fallback: Center
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Utility
    // ═══════════════════════════════════════════════════════════════════════

    private double getWidth(IDiagramModelObject dmo) {
        return dmo.getBounds().getWidth() > 0 ? dmo.getBounds().getWidth() : ELEM_WIDTH;
    }

    private double getHeight(IDiagramModelObject dmo) {
        return dmo.getBounds().getHeight() > 0 ? dmo.getBounds().getHeight() : ELEM_HEIGHT;
    }
}
