package com.archimatetool.elk;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.eclipse.emf.ecore.EClass;
import com.archimatetool.model.*;

public class ElkGraphBuilder {

    private Map<IDiagramModelObject, ElkNode> nodeMap = new HashMap<>();
    private Map<Integer, ElkNode> layerGroups = new HashMap<>();

    public ElkNode buildGraph(IArchimateDiagramModel diagramModel) {
        ElkNode rootNode = ElkGraphUtil.createGraph();

        // 1. Global Hierarchy & Flow Control
        rootNode.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        rootNode.setProperty(CoreOptions.DIRECTION, Direction.UP);
        rootNode.setProperty(CoreOptions.EDGE_ROUTING, org.eclipse.elk.core.options.EdgeRouting.ORTHOGONAL);

        // Critical Fix discovered by User: SEPARATE_CHILDREN allows hierarchical layout
        // with cross-edges.
        rootNode.setProperty(CoreOptions.HIERARCHY_HANDLING,
                org.eclipse.elk.core.options.HierarchyHandling.SEPARATE_CHILDREN);

        // 2. Layering, Alignment Strategy & Spacing
        rootNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_STRATEGY,
                org.eclipse.elk.alg.layered.options.LayeringStrategy.INTERACTIVE);
        rootNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.NODE_PLACEMENT_STRATEGY,
                org.eclipse.elk.alg.layered.options.NodePlacementStrategy.BRANDES_KOEPF);

        // Spacing aesthetics
        rootNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS,
                100.0);
        rootNode.setProperty(CoreOptions.SPACING_NODE_NODE, 100.0);
        rootNode.setProperty(CoreOptions.SPACING_EDGE_NODE, 50.0);

        rootNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.CROSSING_MINIMIZATION_SEMI_INTERACTIVE,
                true);
        rootNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT,
                org.eclipse.elk.alg.layered.options.FixedAlignment.BALANCED);

        // Ensure creation order is respected for the aspects
        rootNode.setProperty(
                org.eclipse.elk.alg.layered.options.LayeredOptions.CROSSING_MINIMIZATION_FORCE_NODE_MODEL_ORDER, true);

        // Create nodes
        for (IDiagramModelObject modelObject : diagramModel.getChildren()) {
            createNode(rootNode, modelObject);
        }

        // Create edges
        for (IDiagramModelObject modelObject : diagramModel.getChildren()) {
            createEdges(modelObject);
        }

        return rootNode;
    }

    private ElkNode getLayerGroup(ElkNode root, int layerId) {
        ElkNode layerGroup = layerGroups.get(layerId);
        if (layerGroup == null) {
            layerGroup = ElkGraphUtil.createNode(root);
            layerGroup.setIdentifier("layer_" + layerId);

            // LayerGroup itself is layered horizontally (Aspects: Passive <- Behavior <-
            // Active)
            layerGroup.setProperty(CoreOptions.DIRECTION, Direction.LEFT);

            layerGroup.setProperty(CoreOptions.PADDING, new org.eclipse.elk.core.math.ElkPadding(40));
            // Ensure child aspect nodes are laid out
            layerGroup.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
            layerGroup.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_STRATEGY,
                    org.eclipse.elk.alg.layered.options.LayeringStrategy.INTERACTIVE);

            // The LayerGroup itself sits in the Root node, which is Direction.UP.
            // We use LAYERING_LAYER_ID so the Root node stacks them vertically.
            layerGroup.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_LAYER_ID, layerId);

            layerGroups.put(layerId, layerGroup);
        }
        return layerGroup;
    }

    private void createNode(ElkNode parentGraph, IDiagramModelObject dmo) {
        ElkNode actualParent = parentGraph;

        // Route into correct Layer group if at root
        if (parentGraph.getParent() == null && dmo instanceof IDiagramModelArchimateObject) {
            IArchimateElement element = ((IDiagramModelArchimateObject) dmo).getArchimateElement();
            if (element != null) {
                int layerId = getLayerPartition(element);
                actualParent = getLayerGroup(parentGraph, layerId);
            }
        }

        ElkNode elkNode = ElkGraphUtil.createNode(actualParent);
        elkNode.setIdentifier(dmo.getId());

        double width = dmo.getBounds().getWidth() > 0 ? dmo.getBounds().getWidth() : 120;
        double height = dmo.getBounds().getHeight() > 0 ? dmo.getBounds().getHeight() : 55;
        elkNode.setWidth(width);
        elkNode.setHeight(height);

        nodeMap.put(dmo, elkNode);

        // If inside a layer group, assign horizontal Aspect column
        if (actualParent != parentGraph && dmo instanceof IDiagramModelArchimateObject) {
            IArchimateElement element = ((IDiagramModelArchimateObject) dmo).getArchimateElement();
            if (element != null) {
                int aspectOrder = getAspectOrder(element);
                // With Direction.LEFT: Passive(0) gets layer 0 (Right), Behavior(1) gets 1
                // (Center), Active(2) gets 2 (Left)
                elkNode.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_LAYER_ID, aspectOrder);
            }
        }

        // Advanced Container Handling
        if (dmo instanceof IDiagramModelContainer) {
            if (dmo instanceof IDiagramModelArchimateObject) {
                IArchimateElement element = ((IDiagramModelArchimateObject) dmo).getArchimateElement();
                if (element != null && (IArchimatePackage.eINSTANCE.getGrouping().isSuperTypeOf(element.eClass()) ||
                        IArchimatePackage.eINSTANCE.getLocation().isSuperTypeOf(element.eClass()))) {
                    elkNode.setProperty(CoreOptions.PADDING, new org.eclipse.elk.core.math.ElkPadding(40, 20, 20, 20));
                    elkNode.setProperty(CoreOptions.DIRECTION, Direction.LEFT);
                }
            } else if (dmo.eClass().getName().equals("DiagramModelGroup")) {
                elkNode.setProperty(CoreOptions.PADDING, new org.eclipse.elk.core.math.ElkPadding(40, 20, 20, 20));
                elkNode.setProperty(CoreOptions.DIRECTION, Direction.LEFT);
            }

            for (IDiagramModelObject child : ((IDiagramModelContainer) dmo).getChildren()) {
                createNode(elkNode, child); // Nesting inside visual groups is preserved
            }
        }
    }

    private void createEdges(IDiagramModelObject dmo) {
        ElkNode sourceElkNode = nodeMap.get(dmo);
        if (sourceElkNode == null)
            return;

        for (Object connectionObj : dmo.getSourceConnections()) {
            if (connectionObj instanceof IDiagramModelArchimateConnection) {
                IDiagramModelArchimateConnection dmc = (IDiagramModelArchimateConnection) connectionObj;
                if (dmc.getTarget() instanceof IDiagramModelObject) {
                    IDiagramModelObject targetDmo = (IDiagramModelObject) dmc.getTarget();
                    ElkNode targetElkNode = nodeMap.get(targetDmo);

                    if (targetElkNode != null) {
                        ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(sourceElkNode, targetElkNode);
                        elkEdge.setIdentifier(dmc.getId());

                        IArchimateRelationship relElement = dmc.getArchimateRelationship();
                        if (relElement != null) {
                            if (relElement instanceof IRealizationRelationship ||
                                    relElement instanceof IAssignmentRelationship ||
                                    relElement instanceof IServingRelationship) {
                                // High priority for serving/realization to flow straight
                                elkEdge.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PRIORITY, 10);
                            } else if (relElement instanceof ITriggeringRelationship ||
                                    relElement instanceof IFlowRelationship) {
                                elkEdge.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PRIORITY, 5);
                            } else if (relElement instanceof IAccessRelationship) {
                                elkEdge.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PRIORITY, 2);
                            } else if (relElement instanceof ICompositionRelationship ||
                                    relElement instanceof IAggregationRelationship) {
                                elkEdge.setProperty(org.eclipse.elk.alg.layered.options.LayeredOptions.PRIORITY, 1);
                            }

                            // If source and target are in the SAME Aspect Column,
                            // force the edge to go UP/DOWN instead of LEFT/RIGHT
                            // to avoid weird horizontal bends inside the same column.
                            if (sourceElkNode.getParent() == targetElkNode.getParent()) {
                                Integer sourceAspect = sourceElkNode.getProperty(
                                        org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_LAYER_ID);
                                Integer targetAspect = targetElkNode.getProperty(
                                        org.eclipse.elk.alg.layered.options.LayeredOptions.LAYERING_LAYER_ID);

                                if (sourceAspect != null && targetAspect != null && sourceAspect.equals(targetAspect)) {
                                    elkEdge.setProperty(org.eclipse.elk.core.options.CoreOptions.PORT_CONSTRAINTS,
                                            org.eclipse.elk.core.options.PortConstraints.FIXED_SIDE);

                                    // For upward flow (like IServing), connect North to South
                                    if (relElement instanceof IServingRelationship
                                            || relElement instanceof IRealizationRelationship) {
                                        sourceElkNode.setProperty(org.eclipse.elk.core.options.CoreOptions.PORT_SIDE,
                                                org.eclipse.elk.core.options.PortSide.NORTH);
                                        targetElkNode.setProperty(org.eclipse.elk.core.options.CoreOptions.PORT_SIDE,
                                                org.eclipse.elk.core.options.PortSide.SOUTH);
                                    } else {
                                        sourceElkNode.setProperty(org.eclipse.elk.core.options.CoreOptions.PORT_SIDE,
                                                org.eclipse.elk.core.options.PortSide.SOUTH);
                                        targetElkNode.setProperty(org.eclipse.elk.core.options.CoreOptions.PORT_SIDE,
                                                org.eclipse.elk.core.options.PortSide.NORTH);
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (connectionObj instanceof IDiagramModelConnection) {
                IDiagramModelConnection dmc = (IDiagramModelConnection) connectionObj;
                if (dmc.getTarget() instanceof IDiagramModelObject) {
                    IDiagramModelObject targetDmo = (IDiagramModelObject) dmc.getTarget();
                    ElkNode targetElkNode = nodeMap.get(targetDmo);
                    if (targetElkNode != null) {
                        ElkEdge elkEdge = ElkGraphUtil.createSimpleEdge(sourceElkNode, targetElkNode);
                        elkEdge.setIdentifier(dmc.getId());
                    }
                }
            }
        }

        if (dmo instanceof IDiagramModelContainer) {
            for (IDiagramModelObject child : ((IDiagramModelContainer) dmo).getChildren()) {
                createEdges(child);
            }
        }
    }

    private int getLayerPartition(IArchimateElement element) {
        int baseIndex = -1;

        // Direction is UP (0 is bottom-most layer, growing upwards)
        if (element instanceof IMotivationElement) {
            baseIndex = 6;
        } else if (element instanceof IStrategyElement) {
            baseIndex = 5;
        } else if (element instanceof IBusinessElement) {
            baseIndex = 4;
        } else if (element instanceof IApplicationElement) {
            baseIndex = 3;
        } else if (element instanceof ITechnologyElement) {
            baseIndex = 2;
        } else if (element instanceof IPhysicalElement) {
            baseIndex = 1;
        } else if (element instanceof IImplementationMigrationElement) {
            baseIndex = 0;
        }

        if (baseIndex == -1) {
            return 14; // Default to top if unknown (or 14 if preserving sub-lane logic space)
        }

        int subLane = isExternal(element) ? 1 : 0;
        return (baseIndex * 2) + subLane;
    }

    private boolean isExternal(IArchimateElement element) {
        String name = element.eClass().getName();
        return name.endsWith("Service") || name.endsWith("Interface");
    }

    return 1;

    }
    // Active Structure (Layer 2) -> Leftmost
    if(element instanceof IActiveStructureElement){return 2;}return 1;}

    public ElkNode getElkNode(IDiagramModelObject dmo) {
        return nodeMap.get(dmo);
    }
}
