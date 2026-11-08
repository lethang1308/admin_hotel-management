package com.example.hotelmanagement.web.admin.controller;

import com.example.hotelmanagement.modules.booking.OperationsStore;
import com.example.hotelmanagement.modules.booking.StayService;

import java.time.LocalDate;
import java.util.*;

final class StayGuestForms {
    private StayGuestForms() {}

    static List<StayService.Guest> parse(
            OperationsStore db,
            List<String> names,
            List<String> types,
            List<String> documentTypes,
            List<String> documents,
            List<String> births,
            List<String> nationalities) {
        int n = names.size();
        db.require(
                n > 0
                        && n <= 100
                        && types.size() == n
                        && documentTypes.size() == n
                        && documents.size() == n
                        && (births == null || births.size() == n)
                        && (nationalities == null || nationalities.size() == n),
                "guests");
        var result = new ArrayList<StayService.Guest>();
        for (int i = 0; i < n; i++) {
            LocalDate birth = null;
            if (births != null && !births.get(i).isBlank())
                try {
                    birth = LocalDate.parse(births.get(i));
                } catch (java.time.format.DateTimeParseException e) {
                    db.require(false, "birth");
                }
            result.add(
                    new StayService.Guest(
                            names.get(i),
                            types.get(i),
                            documentTypes.get(i),
                            documents.get(i),
                            birth,
                            nationalities == null ? null : nationalities.get(i)));
        }
        return result;
    }
}
