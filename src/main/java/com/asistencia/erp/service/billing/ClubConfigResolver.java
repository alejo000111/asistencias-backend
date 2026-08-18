package com.asistencia.erp.service.billing;

import com.asistencia.erp.entity.ClubConfig;
import com.asistencia.erp.entity.Parent;
import com.asistencia.erp.entity.Student;
import com.asistencia.erp.repository.ClubConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClubConfigResolver {

    private final ClubConfigRepository clubConfigRepository;

    public ClubConfig resolveForParent(Parent parent) {
        if (parent == null || parent.getClubId() == null) {
            return null;
        }
        return clubConfigRepository.findByAdminId(parent.getClubId()).orElse(null);
    }

    public ClubConfig resolveForStudent(Student student) {
        if (student == null || student.getClubId() == null) {
            return null;
        }
        // student.clubId == admin.id por diseño del DataSeeder (savedAdmin.setClubId(savedAdmin.getId()))
        // Usamos el mismo patrón que resolveForParent para garantizar aislamiento multi-tenant.
        return clubConfigRepository.findByAdminId(student.getClubId()).orElse(null);
    }
}
