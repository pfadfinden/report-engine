package de.pfadfinden.reports_engine.preprocessor.Metadata;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;

@Entity
public class ParameterMetadata {
    @EmbeddedId
    @JsonIgnore
    public ParameterId id;

    @JsonBackReference
    @ManyToOne
    @MapsId("reportId")
	@JoinColumn(name="report_id")
    public ReportMetadata report;

    public String label = "";
    public String description = "";
    public String comment = "";
    public String type;

    @Embeddable
    public class ParameterId implements Serializable {
        @Column(name = "report_id")
        private String reportId;
    
        @Column(name = "name")
        private String name;
    }


    public ParameterMetadata() {
        this.id = new ParameterId();
    }

    public void setReport(ReportMetadata report) {
        this.report = report;
        this.id.reportId = report.id;
    }

    @JsonProperty("name")
    public void setName(String name) {
        this.id.name = name;
    }

    @JsonProperty("name")
    public String name() {
        return id.name;
    }

}
