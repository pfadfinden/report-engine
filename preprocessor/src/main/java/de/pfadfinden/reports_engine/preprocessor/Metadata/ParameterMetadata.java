package de.pfadfinden.reports_engine.preprocessor.Metadata;

import java.io.Serializable;
import java.util.Objects;

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
    @JoinColumn(name = "report_id")
    public ReportMetadata report;

    public String label = "";
    public String description = "";
    public String comment = "";
    public String type;

    @Embeddable
    public static class ParameterId implements Serializable {
        @Column(name = "report_id")
        private String reportId;

        @Column(name = "name")
        private String name;

        public ParameterId() {
        }

        public ParameterId(String reportId, String name) {
            this.reportId = reportId;
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ParameterId))
                return false;
            ParameterId that = (ParameterId) o;
            return Objects.equals(reportId, that.reportId) && Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reportId, name);
        }
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
