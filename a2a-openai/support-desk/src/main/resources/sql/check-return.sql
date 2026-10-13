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

-- The facts the request_return route decides on, or no row when the order does not exist or belongs to another
-- customer. itemsOnOrder is false when an item is not part of the order or more pieces are returned than ordered.
-- The requested quantities are added up per SKU first, so that listing an item twice cannot double its refund.
SELECT o.delivered_at IS NOT NULL AS "delivered",
       EXTRACT(DAY FROM now() - o.delivered_at) AS "daysSinceDelivery",
       bool_and(i.sku IS NOT NULL) AS "itemsOnOrder",
       SUM(i.price * r.quantity) AS "amount"
FROM orders o
CROSS JOIN (SELECT sku, SUM(quantity) AS quantity
            FROM json_to_recordset(CAST(:#${exchangeProperty.items} AS JSON)) AS requested(sku TEXT, quantity INTEGER)
            GROUP BY sku) AS r
LEFT JOIN order_items i ON i.order_id = o.id AND i.sku = r.sku AND r.quantity BETWEEN 1 AND i.quantity
WHERE o.id = CAST(:#orderId AS INTEGER)
  AND o.customer_id = :#${exchangeProperty.customerId}
GROUP BY o.id
