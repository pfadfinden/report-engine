package de.pfadfinden.reports_engine.executor.Port;

import java.util.Map;

/**
 * Contains everything that must be provided by the user/client to fill a report.
 * 
 * @author juliusstoerrle
 * @version 1.0
 */
public class ReportTaskConfiguration {

    /**
     * Report identifier 
     */
    public final String reportId;

    /**
     * Map of parameters/variables provided to the report
     */
    public final Map<String, Object> parameters;

    /**
     * Reference for caller to retrieve the file from storage
     */
    public final String outputName;

    public ReportTaskConfiguration(
        String reportId,
        Map<String, Object> parameters,
        String outputName
    ) {
        this.reportId = reportId;
        this.parameters = parameters;
        this.outputName = outputName;
    }
}
