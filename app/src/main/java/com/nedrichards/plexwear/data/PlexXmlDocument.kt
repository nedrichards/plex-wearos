package com.nedrichards.plexwear.data

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal fun parsePlexXml(xml: String): Document {
  // Android's DOM factory does not support the Xerces DTD/entity security features.
  // Use SAX to parse untrusted input; the DOM builder only creates an empty document.
  val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
  val handler = PlexDocumentHandler(document)
  val reader = SAXParserFactory.newInstance().apply {
    isNamespaceAware = false
    isValidating = false
  }.newSAXParser().xmlReader
  reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
  reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
  try {
    reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
  } catch (_: SAXNotRecognizedException) {
    // Android Expat lacks this Xerces feature. The mandatory startDTD guard below
    // rejects the document before external DTD loading or internal expansion.
  } catch (_: SAXNotSupportedException) {
    // Implementations that cannot disable DTD loading still must install that guard.
  }
  // Fail closed if the implementation cannot register the DTD-rejection callback.
  reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
  reader.contentHandler = handler
  reader.entityResolver = handler
  reader.errorHandler = handler
  reader.parse(InputSource(xml.reader()))
  return document
}

private class PlexDocumentHandler(private val document: Document) : DefaultHandler2() {
  private var parent: Node = document

  override fun startDTD(name: String?, publicId: String?, systemId: String?) {
    throw SAXException("DOCTYPE declarations are not supported")
  }

  override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
    throw SAXException("External XML entities are not supported")
  }

  override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource {
    throw SAXException("External XML entities are not supported")
  }

  override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
    val element = document.createElement(qName)
    for (index in 0 until attributes.length) {
      element.setAttribute(attributes.getQName(index), attributes.getValue(index))
    }
    parent.appendChild(element)
    parent = element
  }

  override fun endElement(uri: String?, localName: String?, qName: String?) {
    parent = parent.parentNode
  }

  override fun characters(ch: CharArray, start: Int, length: Int) {
    if (parent !== document) parent.appendChild(document.createTextNode(String(ch, start, length)))
  }

  override fun error(exception: SAXParseException) { throw exception }

  override fun fatalError(exception: SAXParseException) { throw exception }
}
