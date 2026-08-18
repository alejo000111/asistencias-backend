package com.asistencia.erp.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "import_batch_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatchLog {
    @Id
    @Column(name = "batch_id")
    private String batchId;

    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_students", joinColumns = @JoinColumn(name = "batch_id"))
    @Column(name = "student_id")
    private List<Long> createdStudentIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_parents", joinColumns = @JoinColumn(name = "batch_id"))
    @Column(name = "parent_id")
    private List<Long> createdParentIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_sedes", joinColumns = @JoinColumn(name = "batch_id"))
    @Column(name = "sede_id")
    private List<Long> createdSedeIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_enrollments", joinColumns = @JoinColumn(name = "batch_id"))
    @Column(name = "enrollment_id")
    private List<Long> createdEnrollmentIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_parent_snapshots", joinColumns = @JoinColumn(name = "batch_id"))
    private List<ParentSnapshot> parentSnapshots = new ArrayList<>();

    @Column(name = "club_id")
    private Long clubId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_batch_financial_logs", joinColumns = @JoinColumn(name = "batch_id"))
    @Column(name = "financial_log_id")
    private List<Long> createdFinancialLogIds = new ArrayList<>();

    @Column(name = "reverted")
    private Boolean reverted = false;
}
