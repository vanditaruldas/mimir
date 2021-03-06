CREATE LENS XLARGESUPPLIER AS SELECT * FROM LARGESUPPLIER WITH MISSING_VALUE('NATIONKEY');
CREATE LENS XLARGEPARTSUPP AS SELECT * FROM LARGEPARTSUPP WITH MISSING_VALUE('PARTKEY', 'SUPPKEY');
CREATE LENS XLARGECUSTOMER AS SELECT * FROM LARGECUSTOMER WITH MISSING_VALUE('NATIONKEY');
CREATE LENS XLARGELINEITEM AS SELECT * FROM LARGELINEITEM WITH MISSING_VALUE('ORDERKEY', 'PARTKEY', 'SUPPKEY');
CREATE LENS XLARGEORDERS AS SELECT * FROM LARGEORDERS WITH MISSING_VALUE('CUSTKEY');
CREATE LENS XLARGENATION AS SELECT * FROM LARGENATION WITH MISSING_VALUE('REGIONKEY');
