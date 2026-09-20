package vulkancommon;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.vulkan.VK10.*;

/** Regression for extension-rich drivers and changing enumeration counts; no GPU required. */
public final class VulkanExtensionsTest
{
    private VulkanExtensionsTest() { }

    public static void main(String[] args)
    {
        int pointer = MemoryStack.stackGet().getPointer();
        for (int device = 0; device < 8; device++)
        {
            Set<String> names = VulkanExtensions.names((count, properties) ->
            {
                count.put(0, 4096);
                if (properties != null)
                    for (int i = 0; i < 4096; i++)
                        properties.get(i).extensionName().put(("VK_test_" + i + "\0").getBytes(StandardCharsets.UTF_8));
                return VK_SUCCESS;
            });
            if (names.size() != 4096 || !names.contains("VK_test_4095"))
                throw new AssertionError("Lost driver extensions");
        }
        int[] fills = {0};
        Set<String> names = VulkanExtensions.names((count, properties) ->
        {
            if (properties == null) count.put(0, 2);
            else if (fills[0]++ == 0) return VK_INCOMPLETE;
            else
            {
                count.put(0, 1);
                properties.get(0).extensionName().put("VK_test_retry\0".getBytes(StandardCharsets.UTF_8));
            }
            return VK_SUCCESS;
        });
        if (fills[0] != 2 || names.size() != 1 || !names.contains("VK_test_retry"))
            throw new AssertionError("Did not retry incomplete enumeration or honor returned count");
        if (pointer != MemoryStack.stackGet().getPointer()) throw new AssertionError("Leaked stack space");
        System.out.println("Large Vulkan extension lists and enumeration retry checks passed");
    }
}
