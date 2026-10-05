package com.example.reports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the paginated report grid on the operations dashboard.
 *
 * One instance is registered as a singleton bean and serves every dashboard
 * request, so all public methods run concurrently on the servlet thread pool.
 */
public class ReportQueryService {

    private static final Logger log = LoggerFactory.getLogger(ReportQueryService.class);

    private static final SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    /** Columns the dashboard is allowed to sort by. */
    private static final Set<String> SORTABLE =
            Set.of("created_at", "title", "owner", "status");

    private static final int MAX_PAGE_SIZE = 200;

    private final DataSource dataSource;

    public ReportQueryService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<ReportRow> search(ReportQuery query) {
        String sql =
                "SELECT r.id, r.title, r.owner, r.status, r.created_at "
                        + "FROM reports r "
                        + "WHERE r.tenant_id = ? AND r.title ILIKE ? "
                        + "ORDER BY " + query.sortColumn() + " " + query.sortDirection() + " "
                        + "LIMIT ? OFFSET ?";

        List<ReportRow> rows = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement st = conn.prepareStatement(sql)) {

            st.setString(1, query.tenantId());
            st.setString(2, "%" + query.titleContains() + "%");
            st.setInt(3, Math.min(query.pageSize(), MAX_PAGE_SIZE));
            st.setInt(4, query.offset());

            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    rows.add(toRow(conn, rs));
                }
            }
        } catch (SQLException e) {
            log.error("report search failed", e);
            return List.of();
        }

        return rows;
    }

    private ReportRow toRow(Connection conn, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String day = DAY_FORMAT.format(new Date(rs.getTimestamp("created_at").getTime()));

        PreparedStatement tagStmt =
                conn.prepareStatement("SELECT tag FROM report_tags WHERE report_id = ?");
        tagStmt.setString(1, id);
        ResultSet tagRs = tagStmt.executeQuery();

        List<String> tags = new ArrayList<>();
        while (tagRs.next()) {
            tags.add(tagRs.getString("tag"));
        }

        return new ReportRow(
                id,
                rs.getString("title"),
                rs.getString("owner"),
                rs.getString("status"),
                day,
                tags);
    }
}
