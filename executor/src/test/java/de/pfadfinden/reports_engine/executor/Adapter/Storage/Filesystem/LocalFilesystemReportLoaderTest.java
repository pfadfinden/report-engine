package de.pfadfinden.reports_engine.executor.Adapter.Storage.Filesystem;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.pfadfinden.reports_engine.executor.FillReportService;
import de.pfadfinden.reports_engine.executor.Port.ReportDefinition;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.design.JRDesignBand;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignReportTemplate;
import net.sf.jasperreports.engine.design.JRDesignSection;
import net.sf.jasperreports.engine.design.JRDesignStaticText;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.util.JRSaver;

/**
 * Verifies that a report referencing a .jrtx style template (via
 * &lt;template&gt;) can be loaded and filled when both files sit side by
 * side under the reports base directory, i.e. that the RepositoryService
 * wired up by LocalFilesystemReportLoader actually resolves the template.
 *
 * The report itself is built with the JRDesign* API rather than parsed from
 * a hand-written .jrxml: JasperReports 7's Jackson-based JRXML format is
 * strict about exact element/attribute names (e.g. no default XML
 * namespace, "element" wrapper tags for band children, "bold" not
 * "isBold"), and getting that exactly right by hand isn't the point of this
 * test - what's under test is template *resolution* at fill time, not JRXML
 * parsing. The .jrtx template itself, referenced only by location (a plain
 * file), is still real hand-written XML, so it does exercise the loader.
 */
class LocalFilesystemReportLoaderTest {

    @Test
    void loadsAndFillsReportWithJrtxTemplate(@TempDir Path reportsBaseDir) throws Exception {
        String reportName = "test_report";
        File reportDir = new File(reportsBaseDir.toFile(), reportName);
        reportDir.mkdirs();

        JasperReport report = JasperCompileManager.compileReport(buildDesignReferencingJrtxTemplate());
        JRSaver.saveObject(report, new File(reportDir, "report.jasper"));

        try (InputStream jrtx = getClass().getClassLoader().getResourceAsStream(reportName + "/TestStyles.jrtx")) {
            Files.copy(jrtx, new File(reportDir, "TestStyles.jrtx").toPath());
        }

        LocalFilesystemReportLoader loader = new LocalFilesystemReportLoader(reportsBaseDir.toFile());
        ReportDefinition reportDefinition = loader.load(reportName);

        Connection conn = mock(Connection.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FillReportService().fill(reportDefinition, new HashMap<>(), conn, output);

        assertTrue(output.size() > 0, "expected a non-empty filled/exported report");
    }

    private JasperDesign buildDesignReferencingJrtxTemplate() throws Exception {
        JasperDesign design = new JasperDesign();
        design.setName("test_report");
        design.setPageWidth(595);
        design.setPageHeight(842);
        design.setColumnWidth(555);
        design.setLeftMargin(20);
        design.setRightMargin(20);
        design.setTopMargin(20);
        design.setBottomMargin(20);

        design.addTemplate(new JRDesignReportTemplate(new JRDesignExpression("\"TestStyles.jrtx\"")));

        JRDesignStaticText staticText = new JRDesignStaticText();
        staticText.setX(0);
        staticText.setY(0);
        staticText.setWidth(200);
        staticText.setHeight(20);
        staticText.setText("Hello");
        staticText.setStyleNameReference("TestStyle");

        JRDesignBand detailBand = new JRDesignBand();
        detailBand.setHeight(20);
        detailBand.addElement(staticText);

        ((JRDesignSection) design.getDetailSection()).addBand(detailBand);

        return design;
    }
}
