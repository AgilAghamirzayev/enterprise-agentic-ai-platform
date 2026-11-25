# Invoice reconciliation exception

Publish `definition.json` as a workflow. Give it an invoice reference and the variance percentage already calculated by the accounting system. The workflow returns the original record when the variance is at most 5%. Above 5%, an approver must review the exact invoice arguments before the platform creates an internal review ticket. No credentials or accounting systems are needed to run the example.

```json
{"invoiceId":"sample-42","variancePercent":7,"subject":"Review invoice variance"}
```

The API smoke suite exercises both sides of the threshold, including an approver rejecting the exception.
