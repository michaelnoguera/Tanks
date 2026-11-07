package vulkanwindow;

import basewindow.*;
import basewindow.transformation.Matrix4;
import basewindow.transformation.Transformation;
import lwjglwindow.LWJGLWindow;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import javax.imageio.ImageIO;

import static org.lwjgl.glfw.GLFW.*;

/** Tanks window backed by the single-pass Vulkan renderer. */
public final class VulkanGameWindow extends LWJGLWindow
{
    public final VulkanDrawList drawing = new VulkanDrawList();
    private VulkanWindow vulkanWindow;
    private VulkanRenderer renderer;
    private VulkanProjection projection;
    private float sceneShadowBrightness = 1;
    private final HashMap<String, TextureData> pendingTextures = new HashMap<>();
    private double u;
    private double v;

    public VulkanGameWindow(String name, int x, int y, int z, IUpdater updater, IDrawer drawer,
                            IWindowHandler handler, boolean vsync, boolean showMouse)
    {
        super(name, x, y, z, updater, drawer, handler, vsync, showMouse);
        this.antialiasingSupported = false;
        this.colorR = this.colorG = this.colorB = this.colorA = 1;
    }

    @Override protected void beforeGlfwInit()
    {
        VulkanWindow.initializeLoader();
    }

    @Override protected void configureWindow()
    {
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
    }

    @Override protected void initializeGraphicsContext()
    {
        vulkanWindow = new VulkanWindow(window);
        renderer = new VulkanRenderer(vulkanWindow, Boolean.getBoolean("tanks.vulkan.validation"), vsync);
        renderer.setDrawing(drawing);
        renderer.setTextureSource(path ->
        {
            try { return getResource(path); }
            catch (FileNotFoundException missing) { return null; }
        });
        for (String path: pendingTextures.keySet())
        {
            TextureData texture = pendingTextures.get(path);
            renderer.registerTexture(path, texture.width, texture.height, texture.rgba);
        }
        pendingTextures.clear();
        projection = new VulkanProjection(drawing);
        setVsync(vsync);
    }

    @Override protected void loop()
    {
        setUpShaders();
        VulkanShapeRenderer shapes = new VulkanShapeRenderer(drawing);
        shapes.configure(this);
        this.shapeRenderer = shapes;
        runFrames();
    }

    @Override protected void beforeWindowDestroy()
    {
        if (renderer != null)
        {
            renderer.close();
            renderer = null;
        }
    }

    @Override protected void afterGlfwTerminate()
    {
        if (vulkanWindow != null)
        {
            vulkanWindow.close();
            vulkanWindow = null;
        }
        org.lwjgl.vulkan.VK.destroy();
    }

    @Override protected void beginFrame()
    {
        drawing.begin();
        projection.setViewport((int) absoluteWidth, (int) absoluteHeight);
    }

    @Override
    protected void presentFrame()
    {
        double requestedShadowMapSize = mainRenderPasses.shadowQuality *
                Math.max(absoluteWidth, absoluteHeight);
        int shadowMapSize = 1;
        if (mainRenderPasses.shadowsEnabled && Double.isFinite(requestedShadowMapSize) &&
                requestedShadowMapSize > 1)
            shadowMapSize = (int) Math.max(2, Math.min(Integer.MAX_VALUE, requestedShadowMapSize));

        renderer.setShadowBrightness(sceneShadowBrightness);
        renderer.setShadowMapSize(shadowMapSize);
        renderer.drawFrame();
    }

    @Override public boolean supportsAdvancedLighting() { return false; }

    @Override public boolean supportsShadows() { return true; }

    @Override
    public void setWindowTitle(String title)
    {
        super.setWindowTitle(title + " — Vulkan");
    }

    public void registerTexture(String path, int width, int height, ByteBuffer rgba)
    {
        textureSX.put(path, width);
        textureSY.put(path, height);
        if (renderer == null)
            pendingTextures.put(path, new TextureData(width, height, rgba));
        else
            renderer.registerTexture(path, width, height, rgba);
    }

    @Override
    public void setAmbientLight(double intensity)
    {
        drawing.setAmbientLight(intensity);
    }

    @Override
    public void setSceneLighting(double light, double shadow)
    {
        drawing.setAmbientLight(light);
        sceneShadowBrightness = 1;
        if (Double.isFinite(light) && light > 0)
        {
            double ratio = shadow / light;
            if (Double.isFinite(ratio))
                sceneShadowBrightness = (float) Math.max(0, Math.min(1, ratio));
        }
    }

    private static double channel(double value)
    {
        return Double.isFinite(value) ? Math.max(0, Math.min(255, value)) : 0;
    }

    @Override
    public void setVsync(boolean enable)
    {
        this.vsync = enable;
        if (renderer != null)
            renderer.setVsync(enable);
    }

    @Override
    public void setColor(double r, double g, double b, double a, double glow)
    {
        setColor(r, g, b, a);
        this.glow = Double.isFinite(glow) ? Math.max(0, Math.min(1, glow)) : 0;
        drawing.setGlow(this.glow);
    }

    @Override
    public void setColor(double r, double g, double b, double a)
    {
        colorR = r / 255;
        colorG = g / 255;
        colorB = b / 255;
        colorA = a / 255;
        glow = 0;
        drawing.setGlow(0);
        drawing.setColor(channel(r), channel(g), channel(b), channel(a));
    }

    @Override
    public void setColor(double r, double g, double b)
    {
        setColor(r, g, b, 255);
    }

    @Override
    public void setUpPerspective()
    {
        projection.setMatrixProjection();
        resetViewState();
        double m = clipMultiplier;
        if (orthographic)
            projection.ortho(-absoluteWidth / 2, absoluteWidth / 2, absoluteHeight / 2, -absoluteHeight / 2,
                    -absoluteDepth * m, absoluteDepth * m);
        else
            projection.frustum(-absoluteWidth / (absoluteDepth * 2) * m, absoluteWidth / (absoluteDepth * 2) * m,
                    absoluteHeight / (absoluteDepth * 2) * m, -absoluteHeight / (absoluteDepth * 2) * m,
                    m, absoluteDepth * m * clipDistMultiplier);
    }

    @Override
    public void loadPerspective()
    {
        Matrix4 lightProjection = null;
        boolean renderScene = mainRenderPasses != null &&
                currentRenderPass == mainRenderPasses.drawPass &&
                mainRenderPasses.shadowsEnabled;

        projection.setShadowCamera(null);
        if (renderScene && supportsShadows())
        {
            projection.setMatrixModelview();
            projection.loadIdentity();
            projection.setMatrixProjection();
            projection.ortho(0, absoluteWidth, absoluteHeight, 0, -absoluteDepth, absoluteDepth);
            applyShadowTransformations();
            for (Transformation transformation: lightBaseTransformation)
                transformation.apply();
            lightProjection = projection.getProjectionMatrix();
        }

        projection.setMatrixModelview();
        projection.loadIdentity();
        setUpPerspective();
        applyTransformations();
        for (Transformation t: baseTransformations) t.apply();

        if (lightProjection != null)
            projection.setShadowCamera(lightProjection);
    }

    @Override
    public void getProjectionMatrix(float[] out)
    {
        float[] m = projection.getProjectionMatrix().toOpenGL();
        System.arraycopy(m, 0, out, 0, Math.min(out.length, m.length));
    }

    @Override
    public Matrix4 getProjectionMatrix()
    {
        return projection.getProjectionMatrix();
    }

    @Override
    public void clearColor()
    {
    }

    @Override
    public void clearDepth()
    {
        drawing.clearDepth();
    }

    @Override
    public void transform(double[] matrix)
    {
        projection.transform(matrix);
    }

    @Override
    public void transform(Matrix4 matrix)
    {
        projection.transform(matrix);
    }

    @Override
    public void addMatrix()
    {
        projection.pushMatrix();
    }

    @Override
    public void removeMatrix()
    {
        projection.popMatrix();
    }

    @Override
    public void setMatrixProjection()
    {
        projection.setMatrixProjection();
    }

    @Override
    public void setMatrixModelview()
    {
        projection.setMatrixModelview();
    }

    void getBillboardBasis(double[] out)
    {
        if (out == null || out.length < 9)
            throw new IllegalArgumentException("Billboard basis needs 9 values");
        out[0] = bbx1;
        out[1] = bby1;
        out[2] = bbz1;
        out[3] = bbx2;
        out[4] = bby2;
        out[5] = bbz2;
        out[6] = bbx3;
        out[7] = bby3;
        out[8] = bbz3;
    }

    double getTextureScaleX(String path)
    {
        return textureSX.getOrDefault(path, 1);
    }

    double getTextureScaleY(String path)
    {
        return textureSY.getOrDefault(path, 1);
    }

    @Override
    public void setTexture(String image)
    {
        if (!textureSX.containsKey(image))
            createImage(image, null);
        currentTexture = image;
        ((VulkanShapeRenderer) shapeRenderer).setBatchTexture(image);
    }

    @Override
    public void setTexture(String image, boolean batch)
    {
        setTexture(image);
    }

    @Override
    public void stopTexture()
    {
        currentTexture = null;
        ((VulkanShapeRenderer) shapeRenderer).setBatchTexture(null);
    }

    @Override
    public void setTextureCoords(double u, double v)
    {
        this.u = u;
        this.v = v;
    }

    @Override
    public void addVertex(double x, double y, double z)
    {
        ((VulkanShapeRenderer) shapeRenderer).addVertex(x, y, z, u, v);
    }

    @Override
    public void addVertex(double x, double y)
    {
        addVertex(x, y, 0);
    }

    @Override
    public void createImage(String image, InputStream input)
    {
        if (input != null && !image.startsWith("/"))
            image = "/" + image;
        try (InputStream in = input == null ? getResource(image) : input)
        {
            if (in == null)
                throw new FileNotFoundException(image);
            BufferedImage source = ImageIO.read(in);
            if (source == null)
                throw new IOException("Unsupported image: " + image);
            int width = source.getWidth();
            int height = source.getHeight();
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            for (int y = 0; y < height; y++)
            {
                for (int x = 0; x < width; x++)
                {
                    int pixel = source.getRGB(x, y);
                    rgba.put((byte) (pixel >> 16))
                            .put((byte) (pixel >> 8))
                            .put((byte) pixel)
                            .put((byte) (pixel >> 24));
                }
            }
            rgba.flip();
            if (renderer != null)
                renderer.registerTexture(image, width, height, rgba);
            else
                pendingTextures.put(image, new TextureData(width, height, rgba));
            textureSX.put(image, width);
            textureSY.put(image, height);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to load texture " + image, e);
        }
    }

    @Override
    public void setUpscaleImages(boolean enabled)
    {
        upscaleImages = enabled;
    }

    @Override
    public void setViewport(int x, int y, int width, int height)
    {
        projection.setViewport(width, height);
    }

    @Override
    public BaseFrameBuffer createFrameBuffer()
    {
        throw new UnsupportedOperationException("Offscreen framebuffers are unavailable in Vulkan");
    }

    @Override
    public void stopFrameBuffer()
    {
    }

    @Override
    public ModelPart createModelPart()
    {
        return new VulkanModelPart(this);
    }

    @Override
    public ModelPart createModelPart(Model model, ArrayList<ModelPart.Shape> shapes, Model.Material material)
    {
        return new VulkanModelPart(this, model, shapes, material);
    }

    @Override
    public PosedModel createPosedModel(Model model)
    {
        return new VulkanPosedModel(model);
    }

    @Override
    public BaseShapeBatchRenderer createShapeBatchRenderer()
    {
        return new VulkanShapeBatchRenderer(this, true);
    }

    @Override
    public BaseShapeBatchRenderer createShapeBatchRenderer(ShaderGroup shader)
    {
        return new VulkanShapeBatchRenderer(this, shader);
    }

    @Override
    public BaseShapeBatchRenderer createStaticBatchRenderer(ShaderGroup shader, boolean color, String texture, boolean normal, int vertices)
    {
        return new VulkanShapeBatchRenderer(this, shader, false, texture, vertices);
    }

    @Override
    public BaseShaderUtil getShaderUtil(ShaderProgram program)
    {
        return new VulkanShaderUtil(program);
    }

    @Override
    public String screenshot(String path, boolean async)
    {
        if (renderer == null) throw new IllegalStateException("Vulkan renderer is not initialized");
        renderer.capture(Paths.get(path));
        return path;
    }

    @Override
    public void enableBackFaceCulling()
    {
    }

    @Override
    public void enableFrontFaceCulling()
    {
    }

    @Override
    public void disableFaceCulling()
    {
    }

    @Override
    public void createLights(ArrayList<double[]> lights, double scale)
    {
    }

    @Override
    public void setDrawOptions(boolean depth, boolean glow)
    {
        batchDepth = depth;
        batchGlow = glow;
        batchDepthMask = depth;
    }

    @Override
    public void setDrawOptions(boolean depth, boolean glow, boolean depthMask)
    {
        batchDepth = depth;
        batchGlow = glow;
        batchDepthMask = depthMask;
    }

    @Override
    public void enableTexture()
    {
    }

    @Override
    public void disableTexture()
    {
        stopTexture();
    }

    @Override
    public void enableDepthtest()
    {
        batchDepth = true;
    }

    @Override
    public void disableDepthtest()
    {
        batchDepth = false;
    }

    @Override
    public void enableDepthmask()
    {
        batchDepthMask = true;
    }

    @Override
    public void disableDepthmask()
    {
        batchDepthMask = false;
    }

    @Override
    public void setGlowBlendFunc()
    {
    }

    @Override
    public void setLightBlendFunc()
    {
    }

    @Override
    public void setTransparentBlendFunc()
    {
    }

    private static final class TextureData
    {
        final int width;
        final int height;
        final ByteBuffer rgba;

        TextureData(int width, int height, ByteBuffer rgba)
        {
            this.width = width;
            this.height = height;
            this.rgba = ByteBuffer.allocateDirect(rgba.remaining());
            this.rgba.put(rgba.duplicate());
            this.rgba.flip();
        }
    }

}
