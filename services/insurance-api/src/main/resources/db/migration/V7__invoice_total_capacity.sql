-- Up to 10,000 validated rows with ten integer premium digits fit without overflow.
ALTER TABLE simulated_invoice ALTER COLUMN amount TYPE numeric(18,2);
