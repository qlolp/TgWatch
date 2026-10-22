# Whole-branch review: regional services

Base: `fc739facdeb888a4a147ca209a7595ef73d4b085`.
Reviewed head: `b795ff2c1b2425c7e49efbb4083a65eef478aaf6`.
One fresh independent whole-branch reviewer inspected the source, callers and approved design.
No Critical findings; four Important findings were accepted for correction.

| Finding | Correction | Regression evidence |
| --- | --- | --- |
| Quoted/example/FAQ/conversation headings could trigger a region warning | Match direct error wording; exclude quoted/article/message contexts and conversational headings in the browser | Shared quoted/FAQ/denial-help fixtures and an executed DOM-boundary test |
| CSS-hidden headings survived public filtering | A candidate requiring CSS/JavaScript rendering remains UNKNOWN; public probes cannot prove its rendered visibility | Shared inline/stylesheet/external-CSS fixtures; browser checks computed style and ancestor visibility |
| Straight/curly “isn’t available … yet” was missed | Normalize apostrophes and recognize direct contraction variants | Shared HTTP200/403 examples and browser wording tests |
| Restore reset the regional fence, admitting an old enable command | Atomically advance the persisted revision while clearing every opt-in/result/notification setting | Android restore test captures the old revision and rejects its enable after restore |

The local RED run reproduced the classification/browser failures before the corrections.
The Android restore regression was added separately before changing the production fence.
Validation and signed publication evidence are recorded in PR59 and its CI/release runs.
No test, signing, audit or baseline gate was waived.
