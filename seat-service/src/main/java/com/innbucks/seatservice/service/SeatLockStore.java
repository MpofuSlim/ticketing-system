package com.innbucks.seatservice.service;

public interface SeatLockStore {

    void put(String key, String owner, long ttlSeconds);

    String get(String key);

    void delete(String key);
}
