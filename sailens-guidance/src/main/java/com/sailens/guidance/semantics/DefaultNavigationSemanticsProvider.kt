package com.sailens.guidance.semantics

/** The semantics Sailens ships with: Cityscapes for segmentation, COCO for detection. */
class DefaultNavigationSemanticsProvider : NavigationSemanticsProvider {

    override fun semanticSegmentationSemantics(): NavigationSemantics = CityscapesNavigationSemantics

    override fun detectionSemantics(): NavigationSemantics = CocoNavigationSemantics
}
