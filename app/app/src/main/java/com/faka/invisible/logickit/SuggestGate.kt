package com.faka.invisible.logickit

import com.faka.invisible.AppTree

/**
 * Is a suggested app worth a place in front of home? An item put in front makes every other pick one item later.
 * Offer it only when the expected gain beats the expected loss: share * saved > (1 - share) * 1, where share = how likely it is the next pick
 * and saved = what its normal path costs minus the position it would take in the front. Pure.
 */
object SuggestGate {
    /** Cost of reaching a leaf the normal way: items heard on the path plus [AppTree.LEVEL] for every list entered below home. */
    fun treeCost(heard: Int, levels: Int) = heard + AppTree.LEVEL * levels

    /** [share] 0..1, [treeCost] of the normal path, [position] the front place it would take (1 = first). A leaf that is already near the top gains nothing: saved must be above 1. */
    fun worth(share: Double, treeCost: Double, position: Int = 1): Boolean {
        val saved = treeCost - position
        return saved > 1.0 && share * saved > (1 - share) * 1.0
    }
}
