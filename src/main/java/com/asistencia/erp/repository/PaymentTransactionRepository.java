package com.asistencia.erp.repository;

import com.asistencia.erp.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByReference(String reference);
    Optional<PaymentTransaction> findByWompiTransactionId(String wompiTransactionId);

    /**
     * Transición atómica de estado (compare-and-swap) para el procesamiento de webhooks de
     * Wompi: solo actualiza la fila si sigue en PENDING. Wompi reintenta la entrega del mismo
     * evento si no responde 2xx a tiempo, así que dos entregas casi simultáneas de la misma
     * "reference" podían, con un simple "leer estado -> decidir -> escribir", pasar ambas la
     * verificación "¿ya se procesó?" antes de que cualquiera confirmara, duplicando el abono.
     * Con este UPDATE condicional en base de datos, solo una de las dos entregas puede ganar
     * la carrera (retorna 1 fila afectada); la otra ve 0 filas y sabe que ya fue procesada.
     */
    @Modifying
    @Query("UPDATE PaymentTransaction t SET t.status = :newStatus, t.wompiTransactionId = :wompiTransactionId " +
           "WHERE t.reference = :reference AND t.status = com.asistencia.erp.entity.PaymentTransaction.TransactionStatus.PENDING")
    int marcarProcesadaSiPendiente(@Param("reference") String reference,
                                    @Param("newStatus") PaymentTransaction.TransactionStatus newStatus,
                                    @Param("wompiTransactionId") String wompiTransactionId);
}
