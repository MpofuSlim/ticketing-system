-- Creates one database per microservice. The Postgres image runs any *.sql
-- in /docker-entrypoint-initdb.d/ once, the first time the data volume is
-- initialized.
CREATE DATABASE user_service;
CREATE DATABASE event_service;
CREATE DATABASE seat_service;
CREATE DATABASE booking_service;
CREATE DATABASE payment_service;
CREATE DATABASE loyalty_service;
