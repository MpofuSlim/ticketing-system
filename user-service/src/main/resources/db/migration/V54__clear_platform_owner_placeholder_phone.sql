-- The seeded platform owner was created with the placeholder phone
-- '0000000000' (DataInitializer). It is not a number: the console printed it
-- as one on the System Users list, and it also worked as a second sign-in name
-- for the owner (login looks an identifier up by phone too). The owner signs in
-- by email. users.phone_number has been nullable since V44 (staff accounts hold
-- none), and the seeder no longer writes a phone.
--
-- Only the owner's row, and only while it still holds the placeholder: an owner
-- whose phone was changed by hand keeps it. One row, so no lock concern.
UPDATE users
   SET phone_number = NULL
 WHERE platform_owner = TRUE
   AND phone_number = '0000000000';
