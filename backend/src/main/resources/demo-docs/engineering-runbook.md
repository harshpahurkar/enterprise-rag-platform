# Engineering Runbook: Deploys, On-Call and Incidents

Owner: Platform team, led by Tomasz Brekke, Director of Engineering. Last reviewed 2 August 2025. This runbook covers the internal Pike Client Portal and its supporting services.

## Environments

Code moves through three environments: dev, staging and production. Dev updates on every merge to main. Staging mirrors production and is refreshed nightly at 02:00 Eastern. Production runs in Toronto with a warm standby in Montreal.

## Deploy process

Production deploys happen on Tuesdays and Thursdays between 10:00 and 15:00 Eastern. There is a deploy freeze on Fridays, on the day before a public holiday, and during the quarter-end close.

Steps for a standard deploy:

1. Open a pull request. It needs one approving review and a green CI run. Services with client data need two reviews.
2. Merge to main. CI builds the image and tags it with the commit hash.
3. The pipeline deploys to staging automatically and runs the smoke suite, which takes about 6 minutes.
4. The release owner starts the production deploy from the deploy dashboard.
5. The rollout is a canary: 5% of traffic for 10 minutes, then 50% for 10 minutes, then 100%.
6. The release owner watches error rate and p95 latency during the canary. If the error rate rises above 1% or p95 latency passes 800 ms, abort and roll back.

Database migrations must be backwards compatible with the previous release. Ship the migration first, deploy code second, and remove old columns in a later release at least 7 days afterwards.

Emergency deploys outside the window need approval from the on-call lead and one director. Record the reason in the deploy log.

## On-call rotation

Each service has a primary and a secondary on-call engineer. Rotations last 7 days and change over on Monday at 10:00 Eastern. The rotation is published in the paging tool and each handover includes a 15-minute call.

The primary must acknowledge a page within 5 minutes. If the primary does not respond in 5 minutes, the page goes to the secondary, and after another 10 minutes to the engineering manager on duty.

On-call engineers receive a stipend of $350 per week on call. A callout between 22:00 and 06:00 earns a day of time off in lieu, taken within the following 30 days. Nobody may be primary on call for more than 2 weeks in any 8-week period.

Keep a laptop and a charged phone with you during your shift, and be able to reach a keyboard within 15 minutes.

## Incident severity levels

| Level | Definition | Response target | Update cadence |
|-------|-----------|-----------------|----------------|
| SEV1 | Full outage or client data exposure | Respond in 5 minutes | Every 30 minutes |
| SEV2 | Major feature broken for many clients | Respond in 15 minutes | Every 60 minutes |
| SEV3 | Minor feature degraded, workaround exists | Respond in 4 hours | Daily |
| SEV4 | Cosmetic issue or small bug | Next business day | As needed |

Declare the higher severity when in doubt and lower it later. A SEV1 or SEV2 needs an incident commander, who is not the person fixing the problem. The commander runs the call, assigns tasks and posts updates to the status channel.

Any incident involving client data is also reported to the Security Office, following the Information Security Policy.

A written post-incident review is due within 5 business days for SEV1 and SEV2. It states the timeline, root cause, impact and follow-up actions with owners and dates.

## Rollback steps

Roll back first, investigate second. A rollback is always preferred to a risky fix under pressure.

1. Announce the rollback in the incident channel.
2. On the deploy dashboard, choose the service and press Roll back to the previous release. This redeploys the last known good image and takes about 4 minutes.
3. If the dashboard is down, run the command `deployctl rollback <service> --to previous` from the bastion host.
4. Confirm that error rate and latency return to normal for 15 minutes.
5. If a database migration was part of the release, do not reverse it automatically. Ask the database owner whether the change is safe to keep. Because migrations are backwards compatible, the old code should run against the new schema.
6. Freeze deploys for that service until the post-incident review is complete.

For a regional failure, fail over to the Montreal standby using the failover runbook. The target recovery time is 30 minutes and the recovery point objective is 5 minutes of data.

## Monitoring

Dashboards live in the monitoring tool under the Portal folder. Alerts page on-call when the 5-minute error rate passes 2%, when p95 latency is over 1,200 ms for 10 minutes, or when disk use is above 85%. Silence an alert only with a linked ticket and an expiry time.

## Contacts

The engineering manager on duty is listed in the paging tool. The escalation line for SEV1 events is 555-0163.
