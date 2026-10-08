package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 检测四边形 → 渲染契约的纯逻辑单测（2026-09-29）。
 *
 * 契约要点：四边形只在「组合就是单个检测框」时透传；百分比与角度同源，
 * 渲染层不会（也不允许）拿 rotation_deg 与百分比 polygon 的夹角比对一致性。
 */
class TextQuadGeometryTest {

    private fun block(
        id: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        polygon: List<QuadPoint>? = null
    ) = TextBlock(id, "t$id", left, top, right, bottom, 0.9f, polygon)

    private fun tiltedQuad(): List<QuadPoint> = listOf(
        QuadPoint(100f, 100f),
        QuadPoint(500f, 220f),
        QuadPoint(480f, 280f),
        QuadPoint(80f, 160f)
    )

    @Test
    fun `single block quad becomes percent polygon`() {
        val quad = TextQuadGeometry.percentPolygon(listOf(block(0, 100f, 100f, 500f, 280f, tiltedQuad())), 1000, 1000)
        assertNotNull(quad)
        assertEquals(4, quad!!.size)
        assertEquals(10f, quad[0].x, 0.01f)
        assertEquals(10f, quad[0].y, 0.01f)
        assertEquals(50f, quad[1].x, 0.01f)
        assertEquals(22f, quad[1].y, 0.01f)
    }

    @Test
    fun `multi block group never carries a single quad`() {
        // 多框合并出来的段/行一定是轴对齐的：带其中一个框的倾角会让遮罩歪掉。
        val blocks = listOf(
            block(0, 100f, 100f, 500f, 200f, tiltedQuad()),
            block(1, 100f, 260f, 500f, 360f, tiltedQuad())
        )
        assertNull(TextQuadGeometry.percentPolygon(blocks, 1000, 1000))
        assertNull(TextQuadGeometry.quadOf(blocks, 1000, 1000).first)
        assertNull(TextQuadGeometry.quadOf(blocks, 1000, 1000).second)
    }

    @Test
    fun `block without quad yields nothing`() {
        assertNull(TextQuadGeometry.percentPolygon(listOf(block(0, 0f, 0f, 10f, 10f)), 100, 100))
    }

    @Test
    fun `degenerate polygon size is rejected`() {
        val threePoints = listOf(QuadPoint(0f, 0f), QuadPoint(1f, 0f), QuadPoint(1f, 1f))
        assertNull(TextQuadGeometry.percentPolygon(listOf(block(0, 0f, 0f, 10f, 10f, threePoints)), 100, 100))
        assertNull(TextQuadGeometry.rotationDeg(threePoints))
        assertNull(TextQuadGeometry.rotationDeg(null))
    }

    @Test
    fun `rotation comes from the same quad as the polygon`() {
        val quad = tiltedQuad()
        val rotation = TextQuadGeometry.rotationDeg(quad)
        assertNotNull(rotation)
        // 倾角明显非零（斜排），方向朝右下。
        assert(rotation!! > 5f) { "unexpected rotation $rotation" }
    }

    @Test
    fun `axis aligned quad reports zero rotation`() {
        val quad = listOf(
            QuadPoint(0f, 0f), QuadPoint(100f, 0f), QuadPoint(100f, 40f), QuadPoint(0f, 40f)
        )
        assertEquals(0f, TextQuadGeometry.rotationDeg(quad)!!, 0.5f)
    }

    @Test
    fun `invalid image size yields nothing`() {
        assertNull(TextQuadGeometry.percentPolygon(listOf(block(0, 0f, 0f, 10f, 10f, tiltedQuad())), 0, 100))
        assertNull(TextQuadGeometry.percentPolygon(listOf(block(0, 0f, 0f, 10f, 10f, tiltedQuad())), 100, 0))
    }

    @Test
    fun `stitched polygon subtracts the top strip and rescales y`() {
        // 拼接图 1200 高（顶条 200）+ 原图 1000 高：y=50%（600px）→ 原图 400px → 40%。
        val polygon = listOf(
            QuadPoint(10f, 50f), QuadPoint(50f, 50f), QuadPoint(50f, 60f), QuadPoint(10f, 60f)
        )
        val mapped = TextQuadGeometry.stitchedPolygonToSource(polygon, 1200, 200, 1000)!!
        assertEquals(10f, mapped[0].x, 0.01f)
        assertEquals(40f, mapped[0].y, 0.01f)
        assertEquals(40f, mapped[1].y, 0.01f)
        assertEquals(52f, mapped[2].y, 0.01f)
        assertEquals(52f, mapped[3].y, 0.01f)
    }

    @Test
    fun `stitched polygon keeps x untouched`() {
        // 拼接图与原图同宽：x 只做基准切换，数值不变。
        val polygon = listOf(
            QuadPoint(12.5f, 10f), QuadPoint(70f, 10f), QuadPoint(70f, 20f), QuadPoint(12.5f, 20f)
        )
        val mapped = TextQuadGeometry.stitchedPolygonToSource(polygon, 1000, 0, 1000)!!
        assertEquals(12.5f, mapped[0].x, 0.01f)
        assertEquals(70f, mapped[1].x, 0.01f)
    }

    @Test
    fun `stitched polygon allows vertical overflow for cross page bubbles`() {
        // 顶边在原图上方 100px：y=-10%（跨图续排），不能被收拢成 0。
        val polygon = listOf(
            QuadPoint(0f, -10f), QuadPoint(100f, -10f), QuadPoint(100f, 10f), QuadPoint(0f, 10f)
        )
        val mapped = TextQuadGeometry.stitchedPolygonToSource(polygon, 1000, 100, 1000)!!
        assertEquals(-20f, mapped[0].y, 0.01f)
    }

    @Test
    fun `stitched polygon returns null on degenerate sizes`() {
        val polygon = listOf(
            QuadPoint(0f, 0f), QuadPoint(1f, 0f), QuadPoint(1f, 1f), QuadPoint(0f, 1f)
        )
        assertNull(TextQuadGeometry.stitchedPolygonToSource(polygon, 0, 0, 1000))
        assertNull(TextQuadGeometry.stitchedPolygonToSource(null, 1000, 0, 1000))
    }
}
