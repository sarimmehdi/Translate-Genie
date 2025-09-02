package com.sarim.translate_genie

import org.w3c.dom.Element

data class BatchItem(
    val id: String,
    val originalResourceType: String,
    val sourceText: String,
    val originalSourceElement: Element,
    val parentResourceName: String? = null,
    val quantityKey: String? = null
) {
    companion object {
        fun generateUniqueId(resourceName: String, resourceType: String, indexIfArray: Int?, quantityIfPlural: String?): String {
            return when (resourceType) {
                "string" -> resourceName
                "item" -> {
                    if (quantityIfPlural != null) {
                        "$resourceName[$quantityIfPlural]"
                    } else if (indexIfArray != null) {
                        "$resourceName[$indexIfArray]"
                    } else {
                        "$resourceName[item_unknown_index]"
                    }
                }
                "string-array" -> "$resourceName@array_parent"
                "plurals" -> "$resourceName@plurals_parent"
                else -> "$resourceName@$resourceType"
            }
        }
    }
}
