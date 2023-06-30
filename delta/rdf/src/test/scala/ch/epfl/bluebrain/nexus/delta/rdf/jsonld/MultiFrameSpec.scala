package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import io.circe.Json
import munit.FunSuite

class MultiFrameSpec extends FunSuite with Fixtures {

  val bgJsonResponse: Json  = jsonContentOf("/jsonld/framing/multi-id.json")
  val context: ContextValue =
    ContextValue(jsonContentOf("/jsonld/framing/search-context.json"))

  implicit class Ops(json: Json) {
    def removeContext: Json =
      json.hcursor.downField("@context").delete.top.get
  }

  test("PatchedCell") {
    val id        = iri"https://bbp.epfl.ch/neurosciencegraph/data/1930647a-4c68-4fef-a7f2-06828655eb6f"
    val compacted = CompactedJsonLd.frame(id, context, bgJsonResponse).accepted
    val expectedDocument = jsonContentOf("/jsonld/framing/expected-id1.json")
    assertEquals(compacted.json.removeContext, expectedDocument)
  }

  test("Trace") {
    val id = iri"https://bbp.epfl.ch/neurosciencegraph/data/traces/7f604b81-b8f6-4831-95a3-3315323770ed"
    val compacted = CompactedJsonLd.frame(id, context, bgJsonResponse).accepted
    val expectedDocument = jsonContentOf("/jsonld/framing/expected-id2.json")
    assertEquals(compacted.json.removeContext, expectedDocument)
  }

  test("NeuronDensity") {
    val id = iri"https://bbp.epfl.ch/neurosciencegraph/data/densities/34cf9e78-c7b4-4bcb-829e-40cce324b5dd"
    val compacted = CompactedJsonLd.frame(id, context, bgJsonResponse).accepted
    val expectedDocument = jsonContentOf("/jsonld/framing/expected-id3.json")
    assertEquals(compacted.json.removeContext, expectedDocument)
  }

  test("NeuronMorphology") {
    val id = iri"https://bbp.epfl.ch/neurosciencegraph/data/neuronmorphologies/3c27e001-6a03-4680-b226-9fe934f4b2ed"
    val compacted = CompactedJsonLd.frame(id, context, bgJsonResponse).accepted
    val expectedDocument = jsonContentOf("/jsonld/framing/expected-id4.json")
    assertEquals(compacted.json.removeContext, expectedDocument)
  }

  test("LayerThickness") {
    val id = iri"https://bbp.epfl.ch/neurosciencegraph/data/9da2884c-bfcd-43d8-9986-202d9a1cda51"
    val compacted = CompactedJsonLd.frame(id, context, bgJsonResponse).accepted
    val expectedDocument = jsonContentOf("/jsonld/framing/expected-id5.json")
    assertEquals(compacted.json.removeContext, expectedDocument)
  }

  test("Subject") {
    val id = iri"https://bbp.epfl.ch/neurosciencegraph/data/f96ceed4-9326-4888-99ea-ada2d29b0235"
    val compacted = CompactedJsonLd.frame(id, context, bgJsonResponse).accepted
    val expectedDocument = jsonContentOf("/jsonld/framing/expected-id6.json")
    assertEquals(compacted.json.removeContext, expectedDocument)
  }
}
