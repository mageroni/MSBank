# eventhubs

Event Hubs namespace exposing the Kafka surface (port 9093). For each entry in
`event_hubs` an event hub (Kafka topic) is created:

- `user-events`
- `account-events`
- `transaction-events`
- `notification-events`

For each entry in `services`, two namespace-level authorization rules are
created:

- `<service>-send`  -- Send-only
- `<service>-listen` -- Listen-only

Connection strings are exported (sensitive). Callers typically write them into
Key Vault to be projected via Secrets Store CSI / workload identity.

> Quirk: `auto_inflate_enabled` and `maximum_throughput_units` are invalid on
> the **Basic** SKU; this module silently disables them in that case. Premium
> uses processing units (PUs) with different semantics -- override
> `capacity` accordingly.
