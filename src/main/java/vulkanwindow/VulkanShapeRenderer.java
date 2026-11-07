package vulkanwindow;

import basewindow.BaseShapeRenderer;

/**
 * Immediate game-shape adapter backed by the ordered Vulkan draw list.
 * Geometry is emitted in game draw order so alpha blending and texture changes
 * retain the behavior expected by Tanks' Drawing facade.
 */
public final class VulkanShapeRenderer extends BaseShapeRenderer
{
    public final VulkanDrawList drawing;
    private boolean batch;
    private boolean batchQuads = true;
    private boolean batchDepth;
    private boolean batchDepthWrite;
    private boolean batchAdditive;
    private String batchTexture;
    private int batchFirst;
    private int batchVertices;
    private final double[] batchVerticesData = new double[20];
    private final float[] batchVertexColors = new float[16];
    private final float[] currentColor = new float[4];
    private VulkanGameWindow window;
    private final double[] billboardBasis = new double[9];

    void configure(VulkanGameWindow gameWindow)
    {
        this.window = gameWindow;
    }

    public VulkanShapeRenderer(VulkanDrawList drawing)
    {
        if (drawing == null)
            throw new IllegalArgumentException("drawing must not be null");
        this.drawing = drawing;
    }
    /** Called by VulkanGameWindow.addVertex while a legacy Tanks batch is open. */

    void addVertex(double x, double y, double z, double u, double v)
    {
        if (!batch)
        {
            int first = drawing.vertexCount();
            drawing.vertex(x, y, z, u, v);
            drawing.finish(first, batchTexture, batchDepth, false);
            return;
        }
        int offset = batchVertices * 5;
        batchVerticesData[offset] = x;
        batchVerticesData[offset + 1] = y;
        batchVerticesData[offset + 2] = z;
        batchVerticesData[offset + 3] = u;
        batchVerticesData[offset + 4] = v;
        drawing.getColor(currentColor);
        System.arraycopy(currentColor, 0, batchVertexColors, batchVertices * 4, 4);
        batchVertices++;
        int verticesPerShape = batchQuads ? 4 : 3;
        if (batchVertices == verticesPerShape)
        {
            int first = batchFirst;
            // The draw list consumes triangles, so split legacy quads while passing triangles through.
            if (batchQuads)
            {
                emitBatchVertex(0);
                emitBatchVertex(1);
                emitBatchVertex(2);
                emitBatchVertex(0);
                emitBatchVertex(2);
                emitBatchVertex(3);
            }
            else
            {
                emitBatchVertex(0);
                emitBatchVertex(1);
                emitBatchVertex(2);
            }
            drawing.finish(first, batchTexture, batchDepth, batchDepthWrite, batchAdditive);
            batchVertices = 0;
            batchFirst = drawing.vertexCount();
        }
    }

    private void emitBatchVertex(int index)
    {
        int offset = index * 5;
        System.arraycopy(batchVertexColors, index * 4, currentColor, 0, 4);
        drawing.vertex(batchVerticesData[offset], batchVerticesData[offset + 1], batchVerticesData[offset + 2], batchVerticesData[offset + 3], batchVerticesData[offset + 4],
                currentColor);
    }

    void setBatchTexture(String texture)
    {
        if (batch && batchVertices != 0)
            throw new IllegalStateException("Texture changed in the middle of a quad");
        if (batch && drawing.vertexCount() != batchFirst)
        {
            drawing.finish(batchFirst, batchTexture, batchDepth, batchDepthWrite, batchAdditive);
            batchFirst = drawing.vertexCount();
        }
        batchTexture = texture;
    }

    private int begin()
    {
        return drawing.vertexCount();
    }

    private void triangle(int first, double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3)
    {
        drawing.vertex(x1, y1, z1, 0, 0);
        drawing.vertex(x2, y2, z2, 0, 0);
        drawing.vertex(x3, y3, z3, 0, 0);
    }

    private void glowTriangle(double x1, double y1, double z1, double x2, double y2, double z2,
                              double x3, double y3, double z3)
    {
        drawing.getColor(currentColor);
        float[] edgeColor = {currentColor[0], currentColor[1], currentColor[2], 0};
        drawing.vertex(x1, y1, z1, 0, 0, edgeColor);
        drawing.vertex(x2, y2, z2, 0, 0, edgeColor);
        drawing.vertex(x3, y3, z3, 0, 0, currentColor);
    }

    private void quad(int first, double x, double y, double z, double w, double h)
    {
        drawing.vertex(x, y, z, 0, 0);
        drawing.vertex(x + w, y, z, 1, 0);
        drawing.vertex(x + w, y + h, z, 1, 1);
        drawing.vertex(x, y, z, 0, 0);
        drawing.vertex(x + w, y + h, z, 1, 1);
        drawing.vertex(x, y + h, z, 0, 1);
    }

    private void appendQuad(double x1, double y1, double z1, double x2, double y2, double z2,
                            double x3, double y3, double z3, double x4, double y4, double z4)
    {
        drawing.vertex(x1, y1, z1, 0, 0);
        drawing.vertex(x2, y2, z2, 1, 0);
        drawing.vertex(x3, y3, z3, 1, 1);
        drawing.vertex(x1, y1, z1, 0, 0);
        drawing.vertex(x3, y3, z3, 1, 1);
        drawing.vertex(x4, y4, z4, 0, 1);
    }

    private void appendBox(double x, double y, double z, double width, double height, double depth,
                           byte options)
    {
        double x2 = x + width;
        double y2 = y + height;
        double z2 = z + depth;
        if ((options & hide_neg_z) == 0)
            appendQuad(x, y, z, x2, y, z, x2, y2, z, x, y2, z);
        if ((options & hide_pos_z) == 0)
            appendQuad(x2, y, z2, x, y, z2, x, y2, z2, x2, y2, z2);
        if ((options & hide_neg_y) == 0)
            appendQuad(x, y, z2, x2, y, z2, x2, y, z, x, y, z);
        if ((options & hide_pos_y) == 0)
            appendQuad(x, y2, z, x2, y2, z, x2, y2, z2, x, y2, z2);
        if ((options & hide_neg_x) == 0)
            appendQuad(x, y, z2, x, y, z, x, y2, z, x, y2, z2);
        if ((options & hide_pos_x) == 0)
            appendQuad(x2, y, z, x2, y, z2, x2, y2, z2, x2, y2, z);
    }

    private void end(int first, boolean depth)
    {
        drawing.getColor(currentColor);
        drawing.finish(first, null, depth, depth && currentColor[3] >= 1);
    }

    private void end(int first, boolean depth, boolean additive)
    {
        drawing.finish(first, null, depth, false, additive);
    }

    private void ring(double x, double y, double z, double outerX, double outerY, double innerX, double innerY, double start, double end, boolean depth)
    {
        int first = begin();
        int segments = Math.max(8, (int) (Math.abs(end - start) * Math.max(outerX, outerY) / 6));
        for (int i = 0; i < segments; i++)
        {
            double a = start + (end - start) * i / segments;
            double b = start + (end - start) * (i + 1) / segments;
            double ox1 = x + Math.cos(a) * outerX / 2;
            double oy1 = y + Math.sin(a) * outerY / 2;
            double ox2 = x + Math.cos(b) * outerX / 2;
            double oy2 = y + Math.sin(b) * outerY / 2;
            double ix1 = x + Math.cos(a) * innerX / 2;
            double iy1 = y + Math.sin(a) * innerY / 2;
            double ix2 = x + Math.cos(b) * innerX / 2;
            double iy2 = y + Math.sin(b) * innerY / 2;
            triangle(first, ox1, oy1, z, ox2, oy2, z, ix2, iy2, z);
            triangle(first, ox1, oy1, z, ix2, iy2, z, ix1, iy1, z);
        }
        end(first, depth);
    }

    @Override
    public void fillOval(double x, double y, double w, double h)
    {
        drawing.fillOval(x, y, 0, w, h, false);
    }

    @Override
    public void fillOval(double x, double y, double z, double w, double h, boolean depth)
    {
        drawing.fillOval(x, y, z, w, h, depth);
    }

    @Override
    public void fillPartialOval(double x, double y, double w, double h, double start, double end)
    {
        start *= Math.PI * 2;
        end *= Math.PI * 2;
        int first = begin();
        int n = Math.max(8, (int) (Math.abs(end - start) * Math.max(w, h) / 6));
        for (int i = 0; i < n; i++)
        {
            double a = start + (end - start) * i / n;
            double b = start + (end - start) * (i + 1) / n;
            triangle(first, x + w / 2, y + h / 2, 0, x + w / 2 + Math.cos(a) * w / 2, y + h / 2 + Math.sin(a) * h / 2, 0, x + w / 2 + Math.cos(b) * w / 2,
                    y + h / 2 + Math.sin(b) * h / 2, 0);
        }
        end(first, false);
    }

    @Override
    public void fillFacingOval(double x, double y, double z, double w, double h, boolean depth)
    {
        fillBillboardOval(x, y, z, w, h, 0, depth);
    }

    @Override
    public void fillFacingOval(double x, double y, double z, double w, double h, double oZ, boolean depth)
    {
        fillBillboardOval(x, y, z, w, h, oZ, depth);
    }

    private void fillBillboardOval(double x, double y, double z, double w, double h, double offsetZ, boolean depth)
    {
        if (window == null)
        {
            fillOval(x, y, z, w, h, depth);
            return;
        }
        window.getBillboardBasis(billboardBasis);
        double cx = x + w / 2;
        double cy = y + h / 2;
        int first = begin();
        int sides = Math.max(4, (int) (w + h + Math.max(z / 20, 0)) / 4 + 5);
        for (int i = 0; i < sides; i++)
        {
            double a = i * Math.PI * 2 / sides;
            double b = (i + 1) * Math.PI * 2 / sides;
            double ax = Math.cos(a) * w / 2;
            double ay = Math.sin(a) * h / 2;
            double bx = Math.cos(b) * w / 2;
            double by = Math.sin(b) * h / 2;
            triangle(first, cx, cy, z, cx + ax * billboardBasis[0] + ay * billboardBasis[3] + offsetZ * billboardBasis[6],
                    cy + ax * billboardBasis[1] + ay * billboardBasis[4] + offsetZ * billboardBasis[7],
                    z + ax * billboardBasis[2] + ay * billboardBasis[5] + offsetZ * billboardBasis[8],
                    cx + bx * billboardBasis[0] + by * billboardBasis[3] + offsetZ * billboardBasis[6],
                    cy + bx * billboardBasis[1] + by * billboardBasis[4] + offsetZ * billboardBasis[7],
                    z + bx * billboardBasis[2] + by * billboardBasis[5] + offsetZ * billboardBasis[8]);
        }
        end(first, depth);
    }

    @Override
    public void fillPartialRing(double x, double y, double size, double thickness, double start, double end)
    {
        ring(x, y, 0, size, size, size - thickness, size - thickness, start * Math.PI * 2, end * Math.PI * 2, false);
    }

    @Override
    public void fillPartialRing(double x, double y, double z, double size, double thickness, double start, double end)
    {
        ring(x, y, z, size, size, size - thickness, size - thickness, start * Math.PI * 2, end * Math.PI * 2, true);
    }

    @Override
    public void fillGlow(double x, double y, double w, double h)
    {
        fillGlow(x, y, w, h, false);
    }

    @Override
    public void fillGlow(double x, double y, double z, double w, double h, boolean depth)
    {
        fillGlow(x, y, z, w, h, depth, false, false);
    }

    @Override
    public void fillGlow(double x, double y, double w, double h, boolean shade)
    {
        fillGlow(x, y, 0, w, h, false, shade, false);
    }

    @Override
    public void fillGlow(double x, double y, double w, double h, boolean shade, boolean light)
    {
        fillGlow(x, y, 0, w, h, false, shade, light);
    }

    @Override
    public void fillGlow(double x, double y, double z, double w, double h, boolean depth, boolean shade)
    {
        fillGlow(x, y, z, w, h, depth, shade, false);
    }

    @Override
    public void fillGlow(double x, double y, double z, double w, double h, boolean depth, boolean shade, boolean light)
    {
        // A layered translucent glow keeps the familiar soft disc while remaining one ordered draw.
        int first = begin();
        int n = 48;
        for (int i = 0; i < n; i++)
        {
            double a = i * Math.PI * 2 / n;
            double b = (i + 1) * Math.PI * 2 / n;
            glowTriangle(x + w / 2 + Math.cos(a) * w / 2, y + h / 2 + Math.sin(a) * h / 2, z,
                    x + w / 2 + Math.cos(b) * w / 2, y + h / 2 + Math.sin(b) * h / 2, z,
                    x + w / 2, y + h / 2, z);
        }
        end(first, depth, !shade || light);
    }

    @Override
    public void fillFacingGlow(double x, double y, double z, double w, double h, boolean depth)
    {
        fillFacingGlow(x, y, z, w, h, depth, false, false);
    }

    @Override
    public void fillFacingGlow(double x, double y, double z, double w, double h, boolean depth, boolean shade)
    {
        fillFacingGlow(x, y, z, w, h, depth, shade, false);
    }

    @Override
    public void fillFacingGlow(double x, double y, double z, double w, double h, boolean depth, boolean shade, boolean light)
    {
        if (window == null)
        {
            fillGlow(x, y, z, w, h, depth, shade, light);
            return;
        }
        window.getBillboardBasis(billboardBasis);
        double cx = x + w / 2;
        double cy = y + h / 2;
        int first = begin();
        int sides = Math.max(4, (int) (w + h + Math.max(z / 20, 0)) / 16 + 5);
        for (int i = 0; i < sides; i++)
        {
            double a = i * Math.PI * 2 / sides;
            double b = (i + 1) * Math.PI * 2 / sides;
            double ax = Math.cos(a) * w / 2;
            double ay = Math.sin(a) * h / 2;
            double bx = Math.cos(b) * w / 2;
            double by = Math.sin(b) * h / 2;
            glowTriangle(cx + ax * billboardBasis[0] + ay * billboardBasis[3], cy + ax * billboardBasis[1] + ay * billboardBasis[4],
                    z + ax * billboardBasis[2] + ay * billboardBasis[5], cx + bx * billboardBasis[0] + by * billboardBasis[3], cy + bx * billboardBasis[1] + by * billboardBasis[4],
                    z + bx * billboardBasis[2] + by * billboardBasis[5], cx, cy, z);
        }
        end(first, depth, !shade || light);
    }

    @Override
    public void drawOval(double x, double y, double w, double h)
    {
        ring(x + w / 2, y + h / 2, 0, w, h, Math.max(0, w - 2), Math.max(0, h - 2), 0, Math.PI * 2, false);
    }

    @Override
    public void drawOval(double x, double y, double z, double w, double h)
    {
        ring(x + w / 2, y + h / 2, z, w, h, Math.max(0, w - 2), Math.max(0, h - 2), 0, Math.PI * 2, true);
    }

    @Override
    public void fillRect(double x, double y, double w, double h)
    {
        if (batch)
        {
            appendQuad(x, y, 0, x + w, y, 0, x + w, y + h, 0, x, y + h, 0);
            return;
        }
        drawing.fillRect(x, y, 0, w, h, false);
    }

    @Override
    public void fillRect(double x, double y, double z, double w, double h, boolean depth)
    {
        if (batch)
        {
            appendQuad(x, y, z, x + w, y, z, x + w, y + h, z, x, y + h, z);
            return;
        }
        drawing.fillRect(x, y, z, w, h, depth);
    }

    @Override
    public void fillRoundedRect(double x, double y, double w, double h, double radius)
    {
        if (radius <= 0.2)
        {
            fillRect(x, y, w, h);
            return;
        }
        double r = Math.min(radius, Math.min(w, h) / 2);
        double[][] centers = {{x + w - r, y + r}, {x + w - r, y + h - r}, {x + r, y + h - r}, {x + r, y + r}};
        double[] starts = {-Math.PI / 2, 0, Math.PI / 2, Math.PI};
        int steps = Math.max(2, Math.min(16, (int) (r / 4) + 2));
        int first = begin();
        double cx = x + w / 2;
        double cy = y + h / 2;
        double px = centers[0][0] + Math.cos(starts[0]) * r;
        double py = centers[0][1] + Math.sin(starts[0]) * r;
        for (int corner = 0; corner < 4; corner++)
        {
            for (int step = 1; step <= steps; step++)
            {
                double angle = starts[corner] + Math.PI / 2 * step / steps;
                double nx = centers[corner][0] + Math.cos(angle) * r;
                double ny = centers[corner][1] + Math.sin(angle) * r;
                triangle(first, cx, cy, 0, px, py, 0, nx, ny, 0);
                px = nx;
                py = ny;
            }
            int nextCorner = (corner + 1) % 4;
            double nx = centers[nextCorner][0] + Math.cos(starts[nextCorner]) * r;
            double ny = centers[nextCorner][1] + Math.sin(starts[nextCorner]) * r;
            if (Math.abs(px - nx) + Math.abs(py - ny) > 0.001)
            {
                triangle(first, cx, cy, 0, px, py, 0, nx, ny, 0);
                px = nx;
                py = ny;
            }
        }
        end(first, false);
    }

    @Override
    public void fillBox(double x, double y, double z, double w, double h, double d, String texture)
    {
        fillBox(x, y, z, w, h, d, (byte) 0, texture);
    }

    @Override
    public void fillBox(double x, double y, double z, double w, double h, double d, byte options, String texture)
    {
        if (batch)
        {
            appendBox(x, y, z, w, h, d, options);
            return;
        }
        drawing.fillBox(x, y, z, w, h, d, options, texture);
    }

    @Override
    public void fillQuad(double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4)
    {
        int first = begin();
        triangle(first, x1, y1, 0, x2, y2, 0, x3, y3, 0);
        triangle(first, x1, y1, 0, x3, y3, 0, x4, y4, 0);
        end(first, false);
    }

    @Override
    public void fillQuadBox(double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4, double z, double depth, byte options)
    {
        int first = begin();
        double[][] points = {{x1, y1}, {x2, y2}, {x3, y3}, {x4, y4}};
        // Each side uses the same hide-bit convention as fillBox.
        int[] sideMasks = {hide_pos_y, hide_neg_y, hide_neg_x, hide_pos_x};
        for (int side = 0; side < 4; side++)
        {
            if ((options & sideMasks[side]) != 0)
                continue;
            int next = (side + 1) % 4;
            triangle(first, points[side][0], points[side][1], z, points[next][0], points[next][1], z, points[next][0], points[next][1], z + depth);
            triangle(first, points[side][0], points[side][1], z, points[next][0], points[next][1], z + depth, points[side][0], points[side][1], z + depth);
        }
        if ((options & hide_neg_z) == 0)
        {
            triangle(first, x1, y1, z, x2, y2, z, x3, y3, z);
            triangle(first, x1, y1, z, x3, y3, z, x4, y4, z);
        }
        if ((options & hide_pos_z) == 0)
        {
            triangle(first, x1, y1, z + depth, x3, y3, z + depth, x2, y2, z + depth);
            triangle(first, x1, y1, z + depth, x4, y4, z + depth, x3, y3, z + depth);
        }
        end(first, true);
    }

    @Override
    public void drawRect(double x, double y, double w, double h)
    {
        drawRect(x, y, w, h, 1, 0);
    }

    @Override
    public void drawRect(double x, double y, double w, double h, double border)
    {
        drawRect(x, y, w, h, border, 0);
    }

    @Override
    public void drawRect(double x, double y, double w, double h, double border, double radius)
    {
        if (border <= 0)
        {
            fillRect(x, y, w, h);
            return;
        }
        fillRect(x, y, w, border);
        fillRect(x, y + h - border, w, border);
        fillRect(x, y + border, border, Math.max(0, h - 2 * border));
        fillRect(x + w - border, y + border, border, Math.max(0, h - 2 * border));
    }

    @Override
    public void drawImage(double x, double y, double w, double h, String image, boolean scaled)
    {
        drawImage(x, y, 0, w, h, 0, 0, 1, 1, image, 0, scaled, false);
    }

    @Override
    public void drawImage(double x, double y, double z, double w, double h, String image, boolean scaled)
    {
        drawImage(x, y, z, w, h, 0, 0, 1, 1, image, 0, scaled, true);
    }

    @Override
    public void drawImage(double x, double y, double w, double h, double u1, double v1, double u2, double v2, String image, boolean scaled)
    {
        drawImage(x, y, 0, w, h, u1, v1, u2, v2, image, 0, scaled, false);
    }

    @Override
    public void drawImage(double x, double y, double z, double w, double h, double u1, double v1, double u2, double v2, String image, boolean scaled)
    {
        drawImage(x, y, z, w, h, u1, v1, u2, v2, image, 0, scaled, true);
    }

    @Override
    public void drawImage(double x, double y, double z, double w, double h, double u1, double v1, double u2, double v2, String image, boolean scaled, boolean depth)
    {
        drawImage(x, y, z, w, h, u1, v1, u2, v2, image, 0, scaled, depth);
    }

    @Override
    public void drawImage(double x, double y, double w, double h, String image, double rotation, boolean scaled)
    {
        drawImage(x, y, 0, w, h, 0, 0, 1, 1, image, rotation, scaled, false);
    }

    @Override
    public void drawImage(double x, double y, double z, double w, double h, String image, double rotation, boolean scaled)
    {
        drawImage(x, y, z, w, h, 0, 0, 1, 1, image, rotation, scaled, true);
    }

    @Override
    public void drawImage(double x, double y, double w, double h, double u1, double v1, double u2, double v2, String image, double rotation, boolean scaled)
    {
        drawImage(x, y, 0, w, h, u1, v1, u2, v2, image, rotation, scaled, false);
    }

    @Override
    public void drawImage(double x, double y, double z, double w, double h, double u1, double v1, double u2, double v2, String image, double rotation, boolean scaled)
    {
        drawImage(x, y, z, w, h, u1, v1, u2, v2, image, rotation, scaled, true);
    }

    @Override

    public void drawImage(
            double x, double y, double z, double w, double h, double u1, double v1, double u2, double v2, String image, double rotation, boolean scaled, boolean depth)
    {
        // The immediate renderer scales requested bounds to the selected UV crop.
        double width = w * (u2 - u1);
        double height = h * (v2 - v1);
        if (scaled && window != null)
        {
            width *= window.getTextureScaleX(image);
            height *= window.getTextureScaleY(image);
        }
        // Rotated legacy images use x/y as their center; unrotated images use the top-left.
        double left = rotation == 0 ? x : x - width / 2;
        double top = rotation == 0 ? y : y - height / 2;
        drawing.drawImage(left, top, z, width, height, u1, v1, u2, v2, image, rotation, depth);
    }

    @Override
    public void setBatchMode(boolean enabled, boolean quads, boolean depth)
    {
        setBatchMode(enabled, quads, depth, false, depth);
    }

    @Override
    public void setBatchMode(boolean enabled, boolean quads, boolean depth, boolean glow)
    {
        setBatchMode(enabled, quads, depth, glow, depth);
    }

    @Override
    public void setBatchMode(boolean enabled, boolean quads, boolean depth, boolean glow, boolean depthMask)
    {
        if (enabled && !batch)
        {
            batch = true;
            batchQuads = quads;
            batchDepth = depth;
            batchDepthWrite = depthMask;
            batchAdditive = glow;
            batchFirst = drawing.vertexCount();
            batchVertices = 0;
        }
        else if (!enabled && batch)
        {
            if (batchVertices != 0)
                throw new IllegalStateException("Tanks batch ended with an incomplete quad");
            drawing.finish(batchFirst, batchTexture, batchDepth, batchDepthWrite, batchAdditive);
            batch = false;
        }
    }
}
