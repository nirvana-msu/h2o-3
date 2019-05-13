#!/bin/bash -ex

# Raise the limit for maximal simultaneous connections higher due to a bug in CDH 6.2 ecosystem
sed -i '/#max_connections/d' /etc/mysql/mysql.conf.d/mysqld.cnf
echo "max_connections        = 200" >> /etc/mysql/mysql.conf.d/mysqld.cnf
