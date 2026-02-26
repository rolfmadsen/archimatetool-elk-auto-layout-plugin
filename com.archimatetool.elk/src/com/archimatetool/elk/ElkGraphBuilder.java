package com.archimatetool.elk;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.LayeringStrategy;
import org.eclipse.elk.alg.layered.options.CrossingMinimizationStrategy;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.*;
import org.eclipse.elk.graph.*;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import com.archimatetool.model.*;

/**
 * ELK Graph Builder for ArchiMate Strict 2D Grid Layout.
 *
 * Implements a precise ArchiMate matrix where:
 *
 * VERTICAL AXIS (Layers, bottom-to-top via Direction.UP):
 * Row 0: Implementation & Migration (bottom)
 * Row 1: Physical
 * Row 2: Technology
 * Row 3: Application
 * Row 4: Business
 * Row 5: Strategy
 * Row 6: Motivation (top)
 *
 * HORIZONTAL AXIS (Aspects, left-to-right via INTERACTIVE crossing):
 * Col 0: Passive Structure (Data Objects, Meaning, Value, Deliverables)
 * Col 1: Behavior (Processes, Functions, Services, Goals, Capabilities)
 * Col 2: Active Structure (Actors, Roles, Components, Interfaces, Nodes)
 *
 * EDGE ROUTING:
 * - Orthogonal (90-degree angles)
 * - Cross-layer (Serving, Realization): prioritized for straight vertical lines
 * - Cross-aspect (Assignment, Access, Flow): prioritized for straight
 * horizontal lines
 *
 * CONTAINERS (Grouping elements):
 * - Act as elastic wrappers around children
 * - Do NOT dictate grid layout; children are individually placed on the matrix
 * - Grid lines pass transparently through containers
 */
public class ElkGraphBuilder {

    private Map<IDiagramModelObject, ElkNode> nodeMap = new HashMap<>();

    public ElkNode buildGraph(IArchimateDiagramModel diagramModel) {
        ElkNode rootNode = ElkGraphUtil.createGraph();

        // ── 1. Global Layout Algorithm ──────────────────────────────────────
        rootNode.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");

        // Direction.UP: Rank 0 at bottom, Rank 6 at top (ArchiMate convention)
        rootNode.setProperty(CoreOptions.DIRECTION, Direction.UP);

        // Allow edge routing to pass through container boundaries cleanly
        rootNode.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN);

        // Orthogonal edge routing: strict 90-degree angles
        rootNode.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);

        // Allow ELK to freely assign ports for cleanest routing
        rootNode.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FREE);

        // ── 2. Strict Matrix Enforcers ──────────────────────────────────────

        // INTERACTIVE layering: strictly obeys per-node LAYERING_LAYER_ID
        // This locks each element to its ArchiMate layer row
        rootNode.setProperty(LayeredOptions.LAYERING_STRATEGY, LayeringStrategy.INTERACTIVE);

        // INTERACTIVE crossing minimization: strictly obeys per-node X coordinates
        // This locks each element to its ArchiMate aspect column
        rootNode.setProperty(LayeredOptions.CROSSING_MINIMIZATION_STRATEGY, CrossingMinimizationStrategy.INTERACTIVE);

        // BRANDES_KOEPF: balanced node placement within layers
        rootNode.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF);

        // ── 3. Spacing ─────────────────────────────────────────────────────
        // Within the same layer (horizontal spacing between siblings)
        rootNode.setProperty(CoreOptions.SPACING_NODE_NODE, 60.0);
        // Between layers (vertical spacing between rows)
        rootNode.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 100.0);

        // ── 4. Build Graph ──────────────────────────────────────────────────
        for (IDiagramModelObject modelObject : diagramModel.getChildren()) {
            createNode(rootNode, modelObject);
        }
        for (IDiagramModelObject modelObject : diagramModel.getChildren()) {
            createEdges(modelObject);
        }

        return rootNode;
    }

    /**
     * Creates an ELK node for a diagram model object.
     *
     * For LEAF elements (actual ArchiMate concepts):
     * - Sets LAYERING_LAYER_ID to lock the vertical row
     * - Sets X position to lock the horizontal aspect column
     *
     * For CONTAINER elements (Grouping, nested boxes):
     * - Acts as an elastic wrapper with padding
     * - Does NOT impose its own grid position
     * - Children are individually placed on the matrix
     */
    private void createNode(ElkNode parentGraph, IDiagramModelObject dmo) {
        ElkNode elkNode = ElkGraphUtil.createNode(parentGraph);
        elkNode.setIdentifier(dmo.getId());

        // Preserve existing dimensions or use sensible defaults
        double width = dmo.getBounds().getWidth() > 0 ? dmo.getBounds().getWidth() : 120;
        double height = dmo.getBounds().getHeight() > 0 ? dmo.getBounds().getHeight() : 55;
        elkNode.setWidth(width);
        elkNode.setHeight(height);

        nodeMap.put(dmo, elkNode);

        boolean hasChildren = dmo instanceof IDiagramModelContainer
                && !((IDiagramModelContainer) dmo).getChildren().isEmpty();

        if (hasChildren) {
            // CONTAINER: Elastic wrapper — just add padding, recurse into children.
            // The container itself does NOT get a layer ID or column position;
            // it transparently wraps around its children without breaking the grid.
            elkNode.setProperty(CoreOptions.PADDING, new ElkPadding(45, 20, 20, 20));
            for (IDiagramModelObject child : ((IDiagramModelContainer) dmo).getChildren()) {
                createNode(elkNode, child);
            }
        } else {
            // LEAF ELEMENT: Lock onto the strict ArchiMate 2D grid
            if (dmo instanceof IDiagramModelArchimateObject) {
                IArchimateElement element = ((IDiagramModelArchimateObject) dmo).getArchimateElement();
                if (element != null) {
                    // Lock vertical position (Y-axis): ArchiMate layer row
                    int layerId = getArchiMateLayerRank(element);
                    elkNode.setProperty(LayeredOptions.LAYERING_LAYER_ID, layerId);

                    // Lock horizontal position (X-axis): ArchiMate aspect column
                    // Multiplied by 600 to create sufficient separation for INTERACTIVE
                    // crossing minimization to preserve the column ordering
                    int aspectColumn = getArchiMateAspectColumn(element);
                    elkNode.setX(aspectColumn * 600);
                }
            }
        }
    }

    /**
     * Creates ELK edges for all connections originating from a diagram object.
     *
     * Edge priorities enforce the grid's routing discipline:
     * - HIGH priority (10): Vertical spine relationships (Serving, Realization)
     * → Forces dead-straight UPWARD lines between layers
     * - MEDIUM priority (8): Structural containment (Composition, Aggregation)
     * → Keeps containment links tight and short
     * - STANDARD priority (5): Cross-aspect behavioral links (Assignment, Access,
     * Flow)
     * → Forces dead-straight HORIZONTAL lines within the same layer
     * - LOW priority (1): All other relationships
     */
    private void createEdges(IDiagramModelObject dmo) {
        ElkNode sourceNode = nodeMap.get(dmo);
        if (sourceNode == null)
            return;

        for (IDiagramModelConnection connection : dmo.getSourceConnections()) {
            ElkNode targetNode = nodeMap.get(connection.getTarget());

            if (targetNode != null) {
                ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(sourceNode, targetNode);
                elkEdge.setIdentifier(connection.getId());

                if (connection instanceof IDiagramModelArchimateConnection) {
                    IArchimateRelationship rel = ((IDiagramModelArchimateConnection) connection)
                            .getArchimateRelationship();

                    // Cross-Layer (vertical spines): dead-straight UPWARD lines
                    if (rel instanceof IServingRelationship || rel instanceof IRealizationRelationship) {
                        elkEdge.setProperty(LayeredOptions.PRIORITY, 10);
                    }
                    // Structural containment: tight vertical grouping
                    else if (rel instanceof ICompositionRelationship || rel instanceof IAggregationRelationship) {
                        elkEdge.setProperty(LayeredOptions.PRIORITY, 8);
                    }
                    // Cross-Aspect (horizontal links): dead-straight horizontal lines
                    else if (rel instanceof IAssignmentRelationship || rel instanceof IAccessRelationship
                            || rel instanceof IFlowRelationship) {
                        elkEdge.setProperty(LayeredOptions.PRIORITY, 5);
                    }
                    // All other relationships
                    else {
                        elkEdge.setProperty(LayeredOptions.PRIORITY, 1);
                    }
                }
            }
        }

        // Recurse into children for nested containers
        if (dmo instanceof IDiagramModelContainer) {
            for (IDiagramModelObject child : ((IDiagramModelContainer) dmo).getChildren()) {
                createEdges(child);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VERTICAL Y-AXIS: ArchiMate Layer Rank
    // Direction.UP means Rank 0 appears at the BOTTOM, Rank 6 at the TOP.
    //
    // Stacking Order (Top → Bottom):
    // 6: Motivation
    // 5: Strategy
    // 4: Business
    // 3: Application
    // 2: Technology
    // 1: Physical
    // 0: Implementation & Migration
    //
    // IMPORTANT: Check order matters for elements with dual interfaces.
    // E.g., IStakeholder is IMotivationElement + IActiveStructureElement.
    // The layer interface must be checked to classify the row correctly.
    // More specific layer types (Strategy, I&M, Physical) are checked
    // before broader ones to handle dual-inheritance correctly.
    // ═══════════════════════════════════════════════════════════════════════
    private int getArchiMateLayerRank(IArchimateElement element) {
        // Strategy layer (check before Business/Behavior due to dual-inheritance)
        // Capability, ValueStream extend IStrategyBehaviorElement (Strategy + Behavior)
        // Resource extends IStrategyElement + IStructureElement
        // CourseOfAction extends IStrategyElement + IBehaviorElement
        if (element instanceof IStrategyElement)
            return 5;

        // Implementation & Migration layer (check before Passive/Behavior due to
        // dual-inheritance)
        // Deliverable, Gap extend I&M + IPassiveStructureElement
        // WorkPackage extends I&M + IBehaviorElement
        // ImplementationEvent extends I&M only
        // Plateau extends I&M + ICompositeElement
        if (element instanceof IImplementationMigrationElement)
            return 0;

        // Physical layer (check before Technology due to some Physical also being
        // Active)
        // Facility, Equipment, DistributionNetwork extend Physical +
        // IActiveStructureElement
        // Material extends Physical + ITechnologyObject (Passive)
        if (element instanceof IPhysicalElement)
            return 1;

        // Motivation layer
        // Goal, Requirement, Principle, Constraint, Driver, Assessment,
        // Outcome, Meaning, Value extend IMotivationElement
        // Stakeholder extends IMotivationElement + IActiveStructureElement
        if (element instanceof IMotivationElement)
            return 6;

        // Business layer
        if (element instanceof IBusinessElement)
            return 4;

        // Application layer
        if (element instanceof IApplicationElement)
            return 3;

        // Technology layer
        if (element instanceof ITechnologyElement)
            return 2;

        // Fallback: place in Business layer (center of diagram)
        return 4;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HORIZONTAL X-AXIS: ArchiMate Aspect Column
    // ELK's INTERACTIVE crossing minimization sorts nodes left-to-right
    // based on their initial X coordinate.
    //
    // Column Order (Left → Right):
    // 0: Passive Structure (Data Objects, Meaning, Value, Deliverables)
    // 1: Behavior (Processes, Functions, Services, Goals, Capabilities)
    // 2: Active Structure (Actors, Roles, Components, Interfaces, Nodes)
    //
    // IMPORTANT: The aspect interfaces (IActiveStructureElement,
    // IPassiveStructureElement, IBehaviorElement) are the MOST SPECIFIC
    // classifiers and must be checked FIRST, before layer-only types.
    //
    // Elements that don't implement any aspect interface are classified
    // by specific type (e.g., Motivation elements without structure/behavior
    // go to their natural aspect based on ArchiMate semantics).
    // ═══════════════════════════════════════════════════════════════════════
    private int getArchiMateAspectColumn(IArchimateElement element) {
        // ── Primary Aspect Interfaces (most reliable, cross-layer) ───────

        // Active Structure: Actors, Roles, Components, Interfaces, Nodes,
        // Collaborations, System Software, Devices, Paths, Networks,
        // Facilities, Equipment, DistributionNetworks, Stakeholder
        if (element instanceof IActiveStructureElement)
            return 2; // Right

        // Passive Structure: BusinessObject, Contract, Representation,
        // DataObject, TechnologyObject/Artifact, Material,
        // Deliverable, Gap
        if (element instanceof IPassiveStructureElement)
            return 0; // Left

        // Behavior: Processes, Functions, Services, Events, Interactions,
        // WorkPackage, CourseOfAction, Capability, ValueStream
        if (element instanceof IBehaviorElement)
            return 1; // Center

        // ── Motivation Aspect Mapping (no structure/behavior interface) ──

        // Meaning and Value are semantically "passive" information
        if (element instanceof IMeaning || element instanceof IValue)
            return 0; // Passive (Left)

        // Goal, Requirement, Principle, Constraint, Driver, Assessment,
        // Outcome are behavioral/intentional concepts
        if (element instanceof IGoal || element instanceof IRequirement
                || element instanceof IPrinciple || element instanceof IConstraint
                || element instanceof IDriver || element instanceof IAssessment
                || element instanceof IOutcome)
            return 1; // Behavior (Center)

        // ── Composite / Other Mapping ───────────────────────────────────

        // Product, Plateau, Grouping, Location are composite containers;
        // place in center as neutral position
        if (element instanceof ICompositeElement)
            return 1; // Center

        // Resource (Strategy): structural concept → Active
        if (element instanceof IResource)
            return 2; // Active (Right)

        // ── Fallback ────────────────────────────────────────────────────
        return 1; // Default: Center (Behavior)
    }

    public ElkNode getElkNode(IDiagramModelObject dmo) {
        return nodeMap.get(dmo);
    }
}