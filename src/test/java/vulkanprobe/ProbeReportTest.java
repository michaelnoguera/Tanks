package vulkanprobe;

import java.util.Arrays;

public final class ProbeReportTest
{
    public static void main(String[] args)
    {
        require(ProbeReport.rejectionReasons(true, true, true, true, 3, true, true).isEmpty(), "valid candidate rejected");
        require(ProbeReport.rejectionReasons(false, false, false, false, 2, false, false).size() == 7, "missing rejection reasons");
        require(ProbeReport.rejectionReasons(true, true, true, true, 2, true, true).size() == 1, "MRT gate missing");
        require(ProbeReport.rejectionReasons(false, true, true, true, 3, true, true).size() == 1, "loader API gate missing");
        require(ProbeReport.rejectionReasons(true, false, true, true, 3, true, true).size() == 1, "device API gate missing");
        String json = ProbeReport.json(ProbeReport.object("escaped", "\"\\\n\t\u0000", "items", Arrays.asList(true, null, 3)));
        require(json.equals("{\"escaped\":\"\\\"\\\\\\u000a\\u0009\\u0000\",\"items\":[true,null,3]}"), "invalid JSON escaping: " + json);
        System.out.println("Probe policy and JSON checks passed");
    }

    private static void require(boolean condition, String message)
    {
        if (!condition) throw new AssertionError(message);
    }
}
