package com.sarim.translate_genie

import org.w3c.dom.Node
import org.w3c.dom.NodeList

fun NodeList.toList(): List<Node> = (0 until length).map { item(it) }
