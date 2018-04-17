# Consuming the Content API Kinesis Feed

The Content API generates a real-time stream of data as changes arrive. This stream reflects changes to both published and unpublished content anywhere in the document body. The data from the stream can be used to synchronize a foreign CMS with Arc, to extract the current state of certain content within Arc as it changes, or to perform limited real-time analytics on publishing changes.

## Getting Access

The Content Kinesis Stream can be made available via to your AWS account upon request. Please ask your engagement manager to get the setup process started.

## Retrieving the data from the streams

A full explanantion of consuming data from Kinesis is available from the [AWS Kinesis Documentation](https://docs.aws.amazon.com/streams/latest/dev/introduction.html).

Kinesis streams consist of *shards* and *records*. Records are ordered within a single shard. At a high level, your application must consume from each shard in sequence to retrieve new records as they arrive and process them.  This document will refer to this part of your application as your *consumer*.

## Retrieving the Arc payload

Kinesis limits the size of a single record to 1 MB. However, the Content API places messages on the stream that contain the entire state of the document at the time of the update. Since these documents are often more than 1MB in size, Content API will place some documents into S3 instead of in the record directly. In these cases, the record will contain a pre-signed S3 URL to retrieve the full payload. (Note that to ensure your consumer is coded to handle this case, some number of updates are randomly saved to S3 regardless of payload size.)

Each Kinesis record will contain either the Content API payload itself (json) or a pre-signed S3 url to retrieve the payload. In either case, it will be gzipped, so your consumer will need to:


Your business logic for retrieving the payload should look something like this:

```python
for record in records['Records']:
    data = record['Data']
    # Initial payload will be compressed
    payload = zlib.decompress(data, 15+32).decode("utf8")
    body = None

    # Handle S3 payloads
    if (payload[0:5] == 'https'):
        print("Fetch: %s" % payload)
        try:
            response = urllib.request.urlopen(payload)
        except urllib.error.HTTPError as e:
            print("\nERROR: Could not fetch %s\n Response was: %s, %s\n" % (payload, e, e.reason))
            print(e.read())
            body = None

        else:
            # Decompress S3 payload
            body = response.read()
            body = zlib.decompress(body, 15+32).decode("utf8")

    else:
        body = payload

    # Body is now Arc Content API payload

```

See also the sample code [on github](https://github.com/washingtonpost/kinesis-customer-sample/).


## Content Operations

Once you've begun retrieving payloads, you'll notice that they are all in a similar format, described by the [Content Operation Schema](https://github.com/washingtonpost/ans-schema/blob/master/src/main/resources/schema/ans/0.6.1/content_operation.json).

Let's dive into the data available here.

* `"type": "content-operation"`

    This should always be present. If it's not present, you are parsing the wrong document.

* `"organization_id": "washpost"`

    This will always be the subdomain of arcpublishing.com that you use to access Arc.

* `"operation": "insert-story"`

    The "operation" identifies what changed in the Content API. The Content API stores metadata for four core types (story, gallery, video, redirect). The data for a single document of one of these types can only be replaced ('insert') or deleted ('delete'). (For purposes of Content API, an update is the same as an insert.)

* `"date": "2018-01-01T12:00:00.0+00:00"`

    The RFC3339-formatted date describing when this operation was completed in the Content API.

* `"id": "ABCDEFGHIJKLMNOPQRSTUVWXYZ"`
  `"branch": "default"`
  `"published": true`

  The fields `id`, `branch` and `published` collectively form the *document key.*  A single document may exist in Content API at any time for each distinct document key. (If you are not using branching, then "branch" is typically set to "default.") For each id + branch, both a published and nonpublished version of the document may co-exist simultaneously. In this way, you can see real-time updates from *both* the draft and published version of the document.  (However, this can also lead to some confusion when processing updates. See the "gotchas" section below.)

* `"created": false`

  On insert operations, this will be set to `true` if a new document was inserted. (I.e., if no document with the same document key existed in Content API at the beginning of the operation.)

* `"trigger" `

    The `trigger` object contains metadata about the *input event* that caused the change in the document. This is useful for distinguishing downstream updates from source updates. If the id and type of the *affected document* are identical to the id and type of *trigger document* then the update was generated by a user editing the document directly. But if the trigger document fields are different (for example, the trigger has `"type":"image"` and `"id":"DEF"` and the affected document has `"type":"story"` and `"id":"ABC"`) then a user updated the *trigger* document, and this update in turn caused the *affected document* to update.

    * `"type": "image"`

    The document type that a user altered which triggered the enclosed document to change.

    * `"id": "DEF"`

    The id of the document that was modified by a user.

    * `"referent_update": true`

    If this update was triggered indirectly, this will be true.

    * `"priority": "ingestion"`

    May be "ingestion" or "standard." Updates with "ingestion" may be pending in the processing queue for longer.

    * `"app_name": "ellipsis"`

    The app where the triggering user action took place, if available.

* `"body":`

    The updated version of the affected document.


## Gotchas

### Tracking the right document

One key thing to be aware of when consuming updates is the structure of the *document key*, described above. Each document in Content API is uniquely identified by (id + branch + published).

This means that processing updates to draft and published documents may be unintuitive. For example, suppose your consumer receives updates in sequence with the following values:

```
{ "id": "ABC", "branch":"default", "published": false, "operation": "insert-story" }

{ "id": "ABC", "branch":"default", "published": true, "operation": "insert-story" }

{ "id": "ABC", "branch":"default", "published": true, "operation": "insert-story" }

{ "id": "ABC", "branch":"default", "published": false, "operation": "insert-story" }

{ "id": "ABC", "branch":"default", "published": true, "operation": "insert-story" }
```

At first glance, it may appear that document "ABC" was saved, then published, published again, then unpublished and then republished.  However that is *incorrect*.

In fact, what this sequence represents is a series of updates to *two copies* of the document. In this example, the draft (nonpublished) document was updated, followed by two updates to the publishe document, then another edit to the draft version, and finally another update to the published document.

### How can I detect publish state change?

The surest way is by having your consumer track state internally and update when it when it sees an insert or a delete for the document in question. However, this requires statefulness on the application side, which is often undesirable.

A decent proxy is checking for `"created": true` on the published document to detect a publish event, and `"operation": "delete-story"` on the unpublish-event.
