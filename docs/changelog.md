
Trumpet Changelog.

## Version 3.0-sqooba

* Kafka SASL support (using HDP version of Kafka, thanks to
  [this commit](https://github.com/hortonworks/kafka-release/commit/d2010c4206ef02071631a1ee1668b63be7b21db7))
* Remove support for CDH/Apache versions
* Watchdogs (EditLog, Producer)
* Java 8 only
* Statsd reporter using `--statsd.enable` option
* Remove graphite
* `src/main/scripts` renamed in `src/main/bin` to be consistent with RPM structure

## Version 2.3.1

* Avoid message leak in the Infinite Streamer
* Make server more integration test friendly (contrib by vgrivel)

## Version 2.3

* [#PR1]() Move examples in a dedicated subproject
* Add Trie data structure for efficient path filtering
* Remove dependency on github.com/jstanier/kafka-broker-discovery
* Add HDP 2.3.4 support
* Clean up dependencies -- importing trumpet-client in downstream project is safe
* Split TrumpetEventStreamer in infinite (relying on Kafka Group Consumer) and Bounded (based on Kafka Simple Consumer)

## Version 2.2

* Initial Public Release
