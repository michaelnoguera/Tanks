package vulkancommon;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;

import java.nio.IntBuffer;
import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.vulkan.VK10.*;

/** Enumerates driver-sized extension lists without consuming LWJGL's fixed thread stack. */
public final class VulkanExtensions
{
    private VulkanExtensions() { }

    @FunctionalInterface
    public interface Query
    {
        int enumerate(IntBuffer count, VkExtensionProperties.Buffer properties);
    }

    public static Set<String> names(Query query)
    {
        try (MemoryStack stack = MemoryStack.stackPush())
        {
            IntBuffer count = stack.mallocInt(1);
            for (int attempt = 0; attempt < 8; attempt++)
            {
                check(query.enumerate(count, null));
                if (count.get(0) == 0) return new TreeSet<>();
                // VkExtensionProperties is 260 bytes. A few hundred extensions exceed the
                // default stack, especially when multiple devices are inspected in one frame.
                try (VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0)))
                {
                    int result = query.enumerate(count, properties);
                    if (result == VK_INCOMPLETE) continue;
                    check(result);
                    Set<String> names = new TreeSet<>();
                    for (int i = 0; i < count.get(0); i++) names.add(properties.get(i).extensionNameString());
                    return names;
                }
            }
        }
        throw new IllegalStateException("Vulkan extension list kept changing during enumeration");
    }

    private static void check(int result)
    {
        if (result != VK_SUCCESS)
            throw new IllegalStateException("Enumerate Vulkan extensions failed (VkResult " + result + ")");
    }
}
