--
-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Opens a ticket and gives back its id and the event announcing it to the support team. The summary comes from the
-- model and is cut to the size of its column rather than failing the call.
INSERT INTO tickets (customer_id, summary, priority)
VALUES (:#${exchangeProperty.customerId}, LEFT(:#summary, 1000), :#priority)
RETURNING id AS "ticketId",
          CAST(json_build_object('ticketId', id, 'customerId', customer_id, 'priority', priority,
                                 'summary', summary) AS TEXT) AS "event"
