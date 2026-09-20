package basewindow;

public abstract class BaseTrueTypeFontRenderer extends BaseFontRenderer
{
    public BaseTrueTypeFontRenderer(BaseWindow window)
    {
        super(window);
    }

    public abstract void addFontsFromDirectory(String directory, int bakeHeight, boolean pixelPerfect,
        double sizeScale, double yOffset);

    public abstract void addSystemFonts(int bakeHeight, boolean pixelPerfect, double sizeScale, double yOffset);
}
