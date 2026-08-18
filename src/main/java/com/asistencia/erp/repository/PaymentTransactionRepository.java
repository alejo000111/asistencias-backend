package com.asistencia.erp.repository;

import com.asistencia.erp.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByReference(String reference);
    Optional<PaymentTransaction> findByWompiTransactionId(String wompiTransactionId);
}
