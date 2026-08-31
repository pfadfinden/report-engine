package de.pfadfinden.reports_engine.preprocessor.Adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.pfadfinden.reports_engine.preprocessor.Exception.FailedToReadReport;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.reports_engine.preprocessor.Metadata.VersionMetadata;

class YamlMetadataReaderTest {

    private final YamlMetadataReader reader = new YamlMetadataReader();

    private File yamlFile(File dir, String content) throws IOException {
        File file = new File(dir, "metadata.yaml");
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void readsScalarAndListFields(@TempDir File dir) throws IOException {
        File file = yamlFile(dir, """
                id: list_of_members
                title: List of Members
                description: Lists all active members.
                outputFormats: [pdf, xlsx]
                """);

        ReportMetadata metadata = reader.read(file);

        assertEquals("list_of_members", metadata.id);
        assertEquals("List of Members", metadata.title);
        assertEquals("Lists all active members.", metadata.description);
        assertEquals(List.of("pdf", "xlsx"), metadata.outputFormats);
    }

    @Test
    void readsNestedVersionHistory(@TempDir File dir) throws IOException {
        File file = yamlFile(dir, """
                id: list_of_members
                title: List of Members
                versionHistory:
                    - version: "1.0"
                    - version: "1.1"
                      createdOn: 2021-10-15
                      description: Sample version
                """);

        ReportMetadata metadata = reader.read(file);

        assertEquals(2, metadata.versionHistory.size());
        VersionMetadata latest = metadata.versionHistory.get(1);
        assertEquals("1.1", latest.version());
        assertEquals(LocalDate.of(2021, 10, 15), latest.createdOn);
        assertEquals("Sample version", latest.description);
    }

    @Test
    void unparsableYamlThrowsFailedToReadReport(@TempDir File dir) throws IOException {
        File file = yamlFile(dir, "id: [unterminated");

        assertThrows(FailedToReadReport.class, () -> reader.read(file));
    }

    @Test
    void defaultsMissingOptionalFieldsRatherThanFailing(@TempDir File dir) throws IOException {
        File file = yamlFile(dir, """
                id: minimal_report
                title: Minimal Report
                """);

        ReportMetadata metadata = reader.read(file);

        assertEquals("", metadata.description);
        assertTrue(metadata.complex != null && !metadata.complex);
    }
}
