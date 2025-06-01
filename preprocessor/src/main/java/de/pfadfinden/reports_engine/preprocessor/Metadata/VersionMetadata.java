package de.pfadfinden.reports_engine.preprocessor.Metadata;

import java.io.Serializable;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;

@Entity
@JsonPropertyOrder({"version","createdOn","description"})
public class VersionMetadata {
    @EmbeddedId
    @JsonIgnore
    public VersionId id;

    @JsonBackReference
    @ManyToOne
    @MapsId("reportId")
	@JoinColumn(name="report_id")
    public ReportMetadata report;

    public LocalDate createdOn;
    public String description = "";

    @Embeddable
    public class VersionId implements Serializable {
        @Column(name = "report_id")
        private String reportId;
    
        @Column(name = "version")
        private String version;
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
