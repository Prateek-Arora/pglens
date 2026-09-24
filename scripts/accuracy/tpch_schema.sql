-- TPC-H-derived schema for the Phase 2.5 accuracy benchmark (ADR-0038). PRIMARY KEYS ONLY, on
-- purpose: every secondary index is something PgLens has to find (or correctly not recommend).
-- Column names/types follow the TPC-H specification's tables; this is a TPC-H-derived workload,
-- not an official TPC result.
CREATE TABLE region (
    r_regionkey integer PRIMARY KEY,
    r_name      char(25) NOT NULL,
    r_comment   varchar(152)
);
CREATE TABLE nation (
    n_nationkey integer PRIMARY KEY,
    n_name      char(25) NOT NULL,
    n_regionkey integer  NOT NULL,
    n_comment   varchar(152)
);
CREATE TABLE part (
    p_partkey     integer PRIMARY KEY,
    p_name        varchar(55)    NOT NULL,
    p_mfgr        char(25)       NOT NULL,
    p_brand       char(10)       NOT NULL,
    p_type        varchar(25)    NOT NULL,
    p_size        integer        NOT NULL,
    p_container   char(10)       NOT NULL,
    p_retailprice numeric(15, 2) NOT NULL,
    p_comment     varchar(23)    NOT NULL
);
CREATE TABLE supplier (
    s_suppkey   integer PRIMARY KEY,
    s_name      char(25)       NOT NULL,
    s_address   varchar(40)    NOT NULL,
    s_nationkey integer        NOT NULL,
    s_phone     char(15)       NOT NULL,
    s_acctbal   numeric(15, 2) NOT NULL,
    s_comment   varchar(101)   NOT NULL
);
CREATE TABLE partsupp (
    ps_partkey    integer        NOT NULL,
    ps_suppkey    integer        NOT NULL,
    ps_availqty   integer        NOT NULL,
    ps_supplycost numeric(15, 2) NOT NULL,
    ps_comment    varchar(199)   NOT NULL,
    PRIMARY KEY (ps_partkey, ps_suppkey)
);
CREATE TABLE customer (
    c_custkey    integer PRIMARY KEY,
    c_name       varchar(25)    NOT NULL,
    c_address    varchar(40)    NOT NULL,
    c_nationkey  integer        NOT NULL,
    c_phone      char(15)       NOT NULL,
    c_acctbal    numeric(15, 2) NOT NULL,
    c_mktsegment char(10)       NOT NULL,
    c_comment    varchar(117)   NOT NULL
);
CREATE TABLE orders (
    o_orderkey      integer PRIMARY KEY,
    o_custkey       integer        NOT NULL,
    o_orderstatus   char(1)        NOT NULL,
    o_totalprice    numeric(15, 2) NOT NULL,
    o_orderdate     date           NOT NULL,
    o_orderpriority char(15)       NOT NULL,
    o_clerk         char(15)       NOT NULL,
    o_shippriority  integer        NOT NULL,
    o_comment       varchar(79)    NOT NULL
);
CREATE TABLE lineitem (
    l_orderkey      integer        NOT NULL,
    l_partkey       integer        NOT NULL,
    l_suppkey       integer        NOT NULL,
    l_linenumber    integer        NOT NULL,
    l_quantity      numeric(15, 2) NOT NULL,
    l_extendedprice numeric(15, 2) NOT NULL,
    l_discount      numeric(15, 2) NOT NULL,
    l_tax           numeric(15, 2) NOT NULL,
    l_returnflag    char(1)        NOT NULL,
    l_linestatus    char(1)        NOT NULL,
    l_shipdate      date           NOT NULL,
    l_commitdate    date           NOT NULL,
    l_receiptdate   date           NOT NULL,
    l_shipinstruct  char(25)       NOT NULL,
    l_shipmode      char(10)       NOT NULL,
    l_comment       varchar(44)    NOT NULL,
    PRIMARY KEY (l_orderkey, l_linenumber)
);
