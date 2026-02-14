package de.pfadfinden.reports_engine.preprocessor.Metadata;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import de.pfadfinden.reports_engine.preprocessor.Metadata.ParameterMetadata.ParameterId;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;

@Entity
@JsonPropertyOrder({ "version", "createdOn", "description" })
public class VersionMetadata {
    @EmbeddedId
    @JsonIgnore
    public VersionId id;

    @JsonBackReference
    @ManyToOne
    @MapsId("reportId")
    @JoinColumn(name = "report_id")
    public ReportMetadata report;

    public LocalDate createdOn;
    public String description = "";

    @Embeddable
    public static class VersionId implements Serializable {
        @Column(name = "report_id")
        private String reportId;

        @Column(name = "version")
        private String version;

        public VersionId() {
        }

        public VersionId(String reportId, String version) {
            this.reportId = reportId;
            this.version = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof VersionId))
                return false;
            VersionId that = (VersionId) o;
            return Objects.equals(reportId, that.reportId) && Objects.equals(version, that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reportId, version);
        }
    }

    public VersionMetadata() {
        this.id = new VersionId();
    }

    public void setReport(ReportMetadata report) {
        this.report = report;
        this.id.reportId = report.id;
    }

    @JsonProperty("version")
    public void setVersion(String version) {
        this.id.version = version;
    }

    @JsonProperty("version")
    public String version() {
        return id.version;
    }

}
