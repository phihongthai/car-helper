package com.carhelper.signs.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxUtilsTest {

    private fun det(id: Int, score: Float, box: Box) = Detection(id, SignLabel("C$id", "c$id"), score, box)

    @Test
    fun `iou of identical boxes is one and disjoint is zero`() {
        val a = Box(0f, 0f, 0.5f, 0.5f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(Box(0.6f, 0.6f, 1f, 1f)), 1e-6f)
    }

    @Test
    fun `iou of half overlap`() {
        val a = Box(0f, 0f, 1f, 1f)
        val b = Box(0.5f, 0f, 1.5f, 1f)
        assertEquals(1f / 3f, a.iou(b), 1e-6f)
    }

    @Test
    fun `nms is class agnostic and keeps highest score`() {
        val box = Box(0.1f, 0.1f, 0.3f, 0.3f)
        val kept = BoxUtils.nms(
            listOf(det(10, 0.6f, box), det(11, 0.8f, box.copy(left = 0.11f)), det(3, 0.7f, Box(0.6f, 0.6f, 0.8f, 0.8f))),
            iouThreshold = 0.45f,
        )
        assertEquals(listOf(11, 3), kept.map { it.classId })
    }

    @Test
    fun `label parser skips comments and falls back to code`() {
        val labels = LabelParser.parse(
            sequenceOf("# header", "", "P.102|Cấm đi ngược chiều", "W.245", "R.303| ", "R.434|Bến xe buýt|im"),
        )
        assertEquals(
            listOf(
                SignLabel("P.102", "Cấm đi ngược chiều"),
                SignLabel("W.245", "W.245"),
                SignLabel("R.303", "R.303"),
                SignLabel("R.434", "Bến xe buýt", silent = true),
            ),
            labels,
        )
    }

    @Test
    fun speedParserReadsValidLimits() {
        assertEquals(60, SpeedParser.parse("60"))
        assertEquals(50, SpeedParser.parse("5O\nkm/h")) // chữ O bị OCR nhầm
        assertEquals(100, SpeedParser.parse("km/h 100"))
        assertEquals(80, SpeedParser.parse("3.5t 80"))
        assertEquals(null, SpeedParser.parse("65"))
        assertEquals(null, SpeedParser.parse(""))
    }
}
