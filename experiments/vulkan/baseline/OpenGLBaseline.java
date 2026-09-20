import basewindow.*;
import lwjglwindow.LWJGLWindow;
import lwjglwindow.TruetypeFontRenderer;
import org.lwjgl.opengl.GL11;
import tanks.*;
import tanks.rendering.RenderPassUI;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Isolated, fixed renderer fixture. Does not run the gameplay update loop. */
public final class OpenGLBaseline
{
    private static Path output;
    private static int updates;
    private static int draws;
    private static int passes;
    private static boolean shadows;
    private static boolean lights;
    private static boolean truetype;

    public static void main(String[] args) throws Exception
    {
        if (args.length != 4 || !args[1].matches("true|false") ||
                !args[2].matches("true|false") || !args[3].matches("true|false"))
            throw new IllegalArgumentException("Usage: OpenGLBaseline OUTPUT SHADOWS LIGHTS TRUETYPE (true|false)");
        output = Paths.get(args[0]).toAbsolutePath();
        // Refuse reuse so a failed run cannot leave apparently successful old evidence.
        Files.createDirectory(output);
        shadows = Boolean.parseBoolean(args[1]);
        lights = Boolean.parseBoolean(args[2]);
        truetype = Boolean.parseBoolean(args[3]);
        Game.framework = Game.Framework.lwjgl;
        Game.disableSteam = true;
        Game.customDir = true;
        Game.directoryPath = output.resolve("user").toString();
        Files.createDirectory(Paths.get(Game.directoryPath));
        Game.game.fileManager = new ComputerFileManager();
        Game.initScript();
        Game.fancyLights = lights;
        Game.soundsEnabled = false;
        Game.musicEnabled = false;
        Game.drawer = new FixtureDrawer();
        Game.game.window = new CaptureWindow();
        Game.postInitScript();
        Game.game.window.run();
    }

    private static final class FixtureDrawer extends GameDrawer
    {
        @Override
        public void initialize()
        {
            tanks.gui.screen.Screen initialScreen = Game.screen;
            super.initialize();
            if (Game.screen != initialScreen || !initialized || uiPass == null)
                throw new IllegalStateException("GameDrawer initialization failed");
            if (truetype)
                Game.game.window.fontRenderer = new TruetypeFontRenderer(
                        (LWJGLWindow) Game.game.window, "/fonts/default/Bullet.ttf", 128, true, 1.4, 0.3);
            uiPass = new RenderPassUI()
            {
                @Override
                public void draw()
                {
                    window.currentRenderPass = this;
                    window.setShader(this.shaderUI);
                    window.loadPerspective();
                    window.setViewport(0, 0, window.frameBufferWidth, window.frameBufferHeight);
                    window.setColor(255, 255, 255, 255);
                    window.fontRenderer.drawString(24, 24, 1, 1, "Tanks baseline 0123456789");
                }
            };
            uiPass.shaderUI = new tanks.rendering.ShaderUIDefault(uiPass);
            try
            {
                uiPass.shaderUI.initialize();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void draw()
        {
            draws++;
            if (!initialized)
                initialize();
            Game.game.window.mainRenderPasses.drawToFramebuffer = lights;
            Game.game.window.mainRenderPasses.draw();
            if (lights)
                lightsPass.draw();
            uiPass.draw();
            if (draws == 31 || draws == 32)
                captureBackBuffer(draws - 1);
        }

        @Override
        public void drawSinglePass(RenderPass pass)
        {
            passes++;
            BaseWindow window = Game.game.window;
            window.setColor(100, 130, 160, 255);
            window.shapeRenderer.fillBox(100, 120, 0, 220, 170, 30, null);
            window.setColor(220, 80, 60, 255);
            window.shapeRenderer.fillBox(240, 200, 30, 100, 90, 70, null);
            window.setColor(60, 220, 100, 128);
            window.shapeRenderer.fillOval(390, 240, 110, 160, 130, true);
        }
    }

    private static void captureBackBuffer(int frame)
    {
        BaseWindow window = Game.game.window;
        int width = window.frameBufferWidth;
        int height = window.frameBufferHeight;
        java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
            {
                int offset = (y * width + x) * 4;
                image.setRGB(x, height - 1 - y, (pixels.get(offset) & 255) << 16 |
                        (pixels.get(offset + 1) & 255) << 8 | (pixels.get(offset + 2) & 255));
            }
        try
        {
            ImageIO.write(image, "png", output.resolve("back-" + frame + ".png").toFile());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static final class CaptureWindow extends LWJGLWindow
    {
        private int frame;
        private PrintWriter samples;

        CaptureWindow() throws IOException
        {
            super("Tanks OpenGL baseline", 640, 480, 1000, () -> updates++, Game.drawer,
                    new IWindowHandler()
                    {
                        public boolean attemptCloseWindow() { return true; }
                        public void onWindowClose() { }
                        public void onFilesDropped(String... paths) { }
                    }, false, false);
            samples = new PrintWriter(Files.newBufferedWriter(output.resolve("frames.csv")));
            samples.println("frame,tick_ns,heap_used_bytes,updates,draws,scene_passes");
        }

        @Override
        protected boolean tick(boolean resizing)
        {
            try
            {
                mainRenderPasses.shadowsEnabled = shadows;
                mainRenderPasses.shadowQuality = 1;
                mainRenderPasses.light = 1;
                mainRenderPasses.shadow = 0.5;
                mainRenderPasses.setLightColor(new Color(255, 255, 255));
                if (frame == 0)
                    writeManifest();
                long start = System.nanoTime();
                boolean closed = super.tick(resizing);
                long elapsed = System.nanoTime() - start;
                Runtime runtime = Runtime.getRuntime();
                samples.printf("%d,%d,%d,%d,%d,%d%n", frame, elapsed,
                        runtime.totalMemory() - runtime.freeMemory(), updates, draws, passes);
                if (frame == 30 || frame == 31)
                    screenshot(output.resolve("frame-" + frame + ".png").toString(), false);
                frame++;
                if (closed || frame == 32)
                {
                    samples.close();
                    if (frame != 32)
                        throw new IllegalStateException("Window closed before captures completed");
                    verifyCaptures();
                    return true;
                }
                return false;
            }
            catch (Exception e)
            {
                samples.close();
                e.printStackTrace();
                System.exit(1);
                return true;
            }
        }

        private void writeManifest() throws IOException
        {
            Properties manifest = new Properties();
            manifest.setProperty("fixture", "fixed-primitives-v1");
            manifest.setProperty("gl.vendor", GL11.glGetString(GL11.GL_VENDOR));
            manifest.setProperty("gl.renderer", GL11.glGetString(GL11.GL_RENDERER));
            manifest.setProperty("gl.version", GL11.glGetString(GL11.GL_VERSION));
            manifest.setProperty("java.version", System.getProperty("java.version"));
            manifest.setProperty("os.name", System.getProperty("os.name"));
            manifest.setProperty("os.arch", System.getProperty("os.arch"));
            manifest.setProperty("shadows", Boolean.toString(shadows));
            manifest.setProperty("fancyLights", Boolean.toString(lights));
            manifest.setProperty("truetype", Boolean.toString(truetype));
            manifest.setProperty("capture", "GL_BACK before swap and GL_FRONT after swap; PNG RGB, top-left origin");
            try (Writer writer = Files.newBufferedWriter(output.resolve("environment.properties")))
            {
                manifest.store(writer, "OpenGL renderer fixture; no gameplay simulation");
            }
        }

        private void verifyCaptures() throws IOException
        {
            BufferedImage a = ImageIO.read(output.resolve("back-30.png").toFile());
            BufferedImage b = ImageIO.read(output.resolve("back-31.png").toFile());
            long changed = 0;
            long nonBackground = 0;
            if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
                throw new IllegalStateException("Missing or incompatible captures");
            int background = a.getRGB(0, 0);
            for (int y = 0; y < a.getHeight(); y++)
                for (int x = 0; x < a.getWidth(); x++)
                {
                    if (a.getRGB(x, y) != b.getRGB(x, y))
                        changed++;
                    if (a.getRGB(x, y) != background)
                        nonBackground++;
                }
            // Check geometry separately from text: a nonblank error screen must not pass.
            int blueBox = a.getRGB(140 * a.getWidth() / 640, 150 * a.getHeight() / 480);
            int redBox = a.getRGB(280 * a.getWidth() / 640, 250 * a.getHeight() / 480);
            int greenOval = a.getRGB(470 * a.getWidth() / 640, 320 * a.getHeight() / 480);
            int blue = blueBox & 255;
            int red = (redBox >> 16) & 255;
            int green = (greenOval >> 8) & 255;
            if (blue < 50 || blue <= ((blueBox >> 16) & 255) ||
                    red < 50 || red <= 2 * ((redBox >> 8) & 255) ||
                    green < 40 || green <= 2 * ((greenOval >> 16) & 255))
                throw new IllegalStateException("Fixture box/oval landmarks missing or incorrectly colored");
            if (updates != 32 || draws != 32 || passes != 32 * (shadows ? 2 : 1))
                throw new IllegalStateException("Unexpected callback counts");
            if (changed != 0 || nonBackground == 0)
                throw new IllegalStateException("Repeat comparison failed: changed=" + changed + ", nonBackground=" + nonBackground);
            BufferedImage front = ImageIO.read(output.resolve("frame-30.png").toFile());
            BufferedImage nextFront = ImageIO.read(output.resolve("frame-31.png").toFile());
            if (front == null || nextFront == null || front.getWidth() != nextFront.getWidth() ||
                    front.getHeight() != nextFront.getHeight())
                throw new IllegalStateException("Missing or incompatible front captures");
            long frontChanged = 0;
            long frontNonBackground = 0;
            for (int y = 0; y < front.getHeight(); y++)
                for (int x = 0; x < front.getWidth(); x++)
                {
                    if (front.getRGB(x, y) != nextFront.getRGB(x, y))
                        frontChanged++;
                    if (front.getRGB(x, y) != front.getRGB(0, 0))
                        frontNonBackground++;
                }
            Files.write(output.resolve("front-status.txt"), ("changed_pixels=" + frontChanged +
                    "\nnon_background_pixels=" + frontNonBackground +
                    "\nscreenshot_semantics_verified=false\n").getBytes("UTF-8"));
            Files.write(output.resolve("verified-back.txt"), ("changed_pixels=0\nnon_background_pixels=" + nonBackground + "\n").getBytes("UTF-8"));
        }
    }
}
