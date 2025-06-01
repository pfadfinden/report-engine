package de.pfadfinden.reports_engine.preprocessor.Adapter;

import java.io.File;
import java.util.stream.Stream;

import de.pfadfinden.reports_engine.preprocessor.Metadata.ParameterMetadata;
import de.pfadfinden.reports_engine.preprocessor.Metadata.ReportMetadata;
import de.pfadfinden.reports_engine.preprocessor.Metadata.VersionMetadata;
import de.pfadfinden.reports_engine.preprocessor.Port.MetadataRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.Query;
import jakarta.persistence.criteria.CriteriaQuery;

public class SqliteMetadataRepository implements MetadataRepository {

    private EntityManager em;

    public static SqliteMetadataRepository initNew(String outputBasePath) {
        return new SqliteMetadataRepository(outputBasePath, "reports.db", true);
    }

    private SqliteMetadataRepository(String outputBasePath, String databaseFileName, Boolean initNew) {
        String jdbcUrl = "jdbc:sqlite:" + outputBasePath + File.separator + databaseFileName;

        EntityManagerFactory emf = new PersistenceConfiguration("ReportDatabase")
                .managedClass(ReportMetadata.class)
                .managedClass(VersionMetadata.class)
                .managedClass(ParameterMetadata.class)
                .property(PersistenceConfiguration.LOCK_TIMEOUT, 5000)
                .property(PersistenceConfiguration.JDBC_URL, jdbcUrl)
                .createEntityManagerFactory();

        assert emf != null;

        if (initNew) {
            emf.getSchemaManager().create(true);
        }

        this.em = emf.createEntityManager();
    }

    public SqliteMetadataRepository(String outputBasePath) {
        this(outputBasePath, "reports.db", false);
    }

    public SqliteMetadataRepository(String outputBasePath, String databaseFileName) {
        this(outputBasePath, databaseFileName, false);
    }

    @Override
    public void add(ReportMetadata report) {
        this.em.getTransaction().begin();
        this.em.persist(report);
        this.em.getTransaction().commit();
    }

    @Override
    public Stream<ReportMetadata> all() {
        CriteriaQuery<ReportMetadata> cq = this.em.getCriteriaBuilder().createQuery(ReportMetadata.class);
        Query query = this.em.createQuery(cq.select(cq.from(ReportMetadata.class)));
        return query.getResultStream();
    }

}
