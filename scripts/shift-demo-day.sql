-- Demo stand only: the seeder lays bookings out starting tomorrow, which makes
-- the schedule look empty to anyone who opens the demo today. Move the first
-- seeded day onto today so the board has something on it from the first visit.
UPDATE bookings
   SET starts_at = starts_at - INTERVAL '1 day',
       ends_at   = ends_at   - INTERVAL '1 day'
 WHERE starts_at::date = (CURRENT_DATE + 1);
