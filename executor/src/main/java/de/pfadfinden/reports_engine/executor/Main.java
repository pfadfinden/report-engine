package de.pfadfinden.reports_engine.executor;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import de.pfadfinden.reports_engine.executor.Exceptions.FailedToExportReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToFillReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToLoadReport;
import de.pfadfinden.reports_engine.executor.Exceptions.FailedToStoreReport;
import de.pfadfinden.reports_engine.executor.Port.ReportTaskConfiguration;
import io.github.cdimascio.dotenv.Dotenv;

public class Main {
    public static void main(String[] args) throws FailedToFillReport, FailedToExportReport, FailedToStoreReport {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        System.out.println(dotenv.get("JASPER_REPORT_ADAPTER_DATASOURCE"));

        // Collecting FIll Data:
                
        Map<String, Object> parameters = new HashMap<String, Object>();
        parameters.put("h_grpId", 236L);

        ReportTaskConfiguration task = new ReportTaskConfiguration("bdp_mitglieder_xls_v1", parameters, "output.xlsx");
        
        try {
            // Setup database connection
            Connection conn = DriverManager.getConnection(dotenv.get("JASPER_REPORT_ADAPTER_DATASOURCE"));
            Statement stmt = conn.createStatement();
            stmt.execute("SET SESSION sql_mode=(SELECT REPLACE(@@sql_mode,'ONLY_FULL_GROUP_BY',''));");


            // Execute report 
            FillReportTaskHandler handler = new FillReportTaskHandler(conn);
            handler.execute(task);


        } catch (SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (FailedToLoadReport e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        System.out.println("Finished report");
    }
}