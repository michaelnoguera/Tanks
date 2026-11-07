package vulkanwindow;

import basewindow.transformation.Matrix4;

import java.util.ArrayDeque;
import java.util.Deque;

/** CPU-side OpenGL-style projection and model-view state for the Vulkan draw list. */
public final class VulkanProjection
{
    private static final Matrix4 VULKAN_CLIP = new Matrix4(new double[][]
    {
            {1, 0, 0, 0},
            {0, -1, 0, 0},
            {0, 0, 0.5, 0.5},
            {0, 0, 0, 1}
    });

    private final VulkanDrawList drawList;
    private final Deque<Matrix4> modelViewStack = new ArrayDeque<Matrix4>();
    private final Deque<Matrix4> projectionStack = new ArrayDeque<Matrix4>();
    private Matrix4 projection = identity();
    private Matrix4 modelView = identity();
    private Matrix4 shadowRemap;
    private boolean projectionMode;
    private int viewportWidth;
    private int viewportHeight;

    public VulkanProjection(VulkanDrawList drawList)
    {
        if (drawList == null)
            throw new IllegalArgumentException("Draw list must not be null");
        this.drawList = drawList;
        updateTransform();
    }

    /** Records the viewport dimensions for callers that need them; it does not alter projection state. */
    public void setViewport(int width, int height)
    {
        if (width < 0 || height < 0)
            throw new IllegalArgumentException("Viewport dimensions must be non-negative");
        viewportWidth = width;
        viewportHeight = height;
    }

    public int getViewportWidth() { return viewportWidth; }

    public int getViewportHeight() { return viewportHeight; }

    public Matrix4 getProjectionMatrix()
    {
        return projection.makeCopy();
    }

    public Matrix4 getModelViewMatrix()
    {
        return modelView.makeCopy();
    }

    /** Sets a shadow camera matrix in OpenGL clip convention, or null to disable shadow coordinates. */
    public void setShadowCamera(Matrix4 lightProjection)
    {
        if (lightProjection == null)
        {
            shadowRemap = null;
            drawList.setShadowTransform(null);
            return;
        }

        Matrix4 camera = projection.multiply(modelView);
        try
        {
            shadowRemap = VULKAN_CLIP.multiply(lightProjection).multiply(camera.inverse());
        }
        catch (RuntimeException e)
        {
            shadowRemap = null;
        }
        updateTransform();
    }

    /** Sets the projection matrix using conventional OpenGL clip coordinates (z in [-w,w]). */
    public void setProjectionMatrix(Matrix4 matrix)
    {
        if (matrix == null)
            throw new IllegalArgumentException("Projection matrix must not be null");
        projection = matrix.makeCopy();
        updateTransform();
    }

    public void setProjectionIdentity()
    {
        projection = identity();
        updateTransform();
    }

    public void loadIdentity()
    {
        setActiveMatrix(identity());
        updateTransform();
    }

    public void setMatrixProjection() { projectionMode = true; }

    public void setMatrixModelview() { projectionMode = false; }

    /** OpenGL glFrustum equivalent. */
    public void frustum(double left, double right, double bottom, double top, double near, double far)
    {
        if (left == right || bottom == top || near <= 0 || far <= 0 || near == far)
            throw new IllegalArgumentException("Invalid frustum bounds");
        double[][] m = new double[4][4];
        m[0][0] = 2 * near / (right - left);
        m[0][2] = (right + left) / (right - left);
        m[1][1] = 2 * near / (top - bottom);
        m[1][2] = (top + bottom) / (top - bottom);
        m[2][2] = -(far + near) / (far - near);
        m[2][3] = -(2 * far * near) / (far - near);
        m[3][2] = -1;
        projection = new Matrix4(m);
        updateTransform();
    }

    /** OpenGL glOrtho equivalent. */
    public void ortho(double left, double right, double bottom, double top, double near, double far)
    {
        if (left == right || bottom == top || near == far)
            throw new IllegalArgumentException("Invalid orthographic bounds");
        double[][] m = new double[4][4];
        m[0][0] = 2 / (right - left);
        m[0][3] = -(right + left) / (right - left);
        m[1][1] = 2 / (top - bottom);
        m[1][3] = -(top + bottom) / (top - bottom);
        m[2][2] = -2 / (far - near);
        m[2][3] = -(far + near) / (far - near);
        m[3][3] = 1;
        projection = new Matrix4(m);
        updateTransform();
    }

    public void pushMatrix()
    {
        activeStack().push(activeMatrix().makeCopy());
    }

    public void popMatrix()
    {
        Deque<Matrix4> stack = activeStack();
        if (stack.isEmpty())
            throw new IllegalStateException("Model-view matrix stack is empty");
        setActiveMatrix(stack.pop());
        updateTransform();
    }

    public int getMatrixStackDepth()
    {
        return activeStack().size();
    }

    public void translate(double x, double y, double z)
    {
        double[][] m = identity().values;
        m[0][3] = x;
        m[1][3] = y;
        m[2][3] = z;
        transform(new Matrix4(m));
    }

    public void scale(double x, double y, double z)
    {
        double[][] m = identity().values;
        m[0][0] = x;
        m[1][1] = y;
        m[2][2] = z;
        transform(new Matrix4(m));
    }

    /** OpenGL-style axis-angle rotation in degrees. */
    public void rotate(double degrees, double x, double y, double z)
    {
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0)
            return;
        x /= length;
        y /= length;
        z /= length;
        double radians = Math.toRadians(degrees);
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        double t = 1 - c;
        double[][] m = identity().values;
        m[0][0] = t * x * x + c;
        m[0][1] = t * x * y - s * z;
        m[0][2] = t * x * z + s * y;
        m[1][0] = t * x * y + s * z;
        m[1][1] = t * y * y + c;
        m[1][2] = t * y * z - s * x;
        m[2][0] = t * x * z - s * y;
        m[2][1] = t * y * z + s * x;
        m[2][2] = t * z * z + c;
        transform(new Matrix4(m));
    }

    /** Rebuilds the Vulkan transform: x unchanged, y flipped, and depth remapped to [0,w]. */
    public void updateTransform()
    {
        Matrix4 clip = VULKAN_CLIP.multiply(projection).multiply(modelView);
        drawList.setTransform(clip.toOpenGL());
        if (shadowRemap == null)
            drawList.setShadowTransform(null);
        else
            drawList.setShadowTransform(shadowRemap.multiply(projection).multiply(modelView).toOpenGL());
    }

    /** Post-multiplies the currently selected OpenGL matrix. */
    public void transform(Matrix4 transform)
    {
        if (transform == null)
            throw new IllegalArgumentException("Transform must not be null");
        setActiveMatrix(activeMatrix().multiply(transform));
        updateTransform();
    }

    /** Multiplies by a column-major 4x4 matrix, as expected by glMultMatrixd. */
    public void transform(double[] columnMajor)
    {
        if (columnMajor == null || columnMajor.length != 16)
            throw new IllegalArgumentException("Transform must contain 16 values");
        double[][] rows = new double[4][4];
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 4; row++)
                rows[row][column] = columnMajor[column * 4 + row];
        transform(new Matrix4(rows));
    }

    private Matrix4 activeMatrix() { return projectionMode ? projection : modelView; }

    private void setActiveMatrix(Matrix4 matrix)
    {
        if (projectionMode)
            projection = matrix;
        else
            modelView = matrix;
    }

    private Deque<Matrix4> activeStack() { return projectionMode ? projectionStack : modelViewStack; }

    private static Matrix4 identity()
    {
        return new Matrix4(new double[][]
        {
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        });
    }
}
