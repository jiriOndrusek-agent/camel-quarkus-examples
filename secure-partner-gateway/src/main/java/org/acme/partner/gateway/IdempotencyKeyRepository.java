/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acme.partner.gateway;

import java.sql.SQLException;

import jakarta.persistence.EntityManagerFactory;
import org.apache.camel.Exchange;
import org.apache.camel.component.jpa.TransactionStrategy;
import org.apache.camel.processor.idempotent.jpa.JpaMessageIdRepository;

/**
 * Idempotency keys of new orders. When two requests insert the same key at the same time, the database rejects
 * the second insert. camel-jpa treats that as a duplicate only for {@code SQLIntegrityConstraintViolationException},
 * which the PostgreSQL driver does not throw, so this class also accepts the SQL state class 23.
 */
class IdempotencyKeyRepository extends JpaMessageIdRepository {

    IdempotencyKeyRepository(EntityManagerFactory entityManagerFactory, TransactionStrategy transactionStrategy) {
        super(entityManagerFactory, transactionStrategy, "order-create");
    }

    @Override
    public boolean add(Exchange exchange, String messageId) {
        try {
            return super.add(exchange, messageId);
        } catch (RuntimeException e) {
            if (isIntegrityConstraintViolation(e)) {
                // a concurrent request with the same key was first
                return false;
            }
            throw e;
        }
    }

    private static boolean isIntegrityConstraintViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }
}
