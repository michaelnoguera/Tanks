package vulkanprobe;

import java.util.*;

/** JSON output and the provisional renderer gate, independent of native APIs. */
final class ProbeReport
{
    private ProbeReport() { }

    static Map<String, Object> object(Object... pairs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
            result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static List<String> rejectionReasons(boolean loaderApi11, boolean deviceApi11, boolean graphics, boolean swapchain,
                                        int colorAttachments, boolean color, boolean depth)
    {
        List<String> reasons = new ArrayList<>();
        if (!loaderApi11) reasons.add("Vulkan 1.1 loader required");
        if (!deviceApi11) reasons.add("Vulkan 1.1 device required");
        if (!graphics) reasons.add("No graphics queue");
        if (!swapchain) reasons.add("VK_KHR_swapchain missing");
        if (colorAttachments < 3) reasons.add("Three color attachments required");
        if (!color) reasons.add("No sampled, blendable RGBA color attachment format");
        if (!depth) reasons.add("No sampled depth attachment format");
        return reasons;
    }

    static String json(Object value)
    {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map)
        {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry: ((Map<?, ?>) value).entrySet())
                entries.add(json(entry.getKey()) + ":" + json(entry.getValue()));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable)
        {
            List<String> entries = new ArrayList<>();
            for (Object entry: (Iterable<?>) value) entries.add(json(entry));
            return "[" + String.join(",", entries) + "]";
        }
        StringBuilder result = new StringBuilder("\"");
        for (char c: value.toString().toCharArray())
        {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}
