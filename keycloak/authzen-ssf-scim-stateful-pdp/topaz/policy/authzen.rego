package authzen.POST.access.v1.evaluation

import rego.v1

default allowed := false

allowed if {
    obj := ds.object({"object_type": "identity", "object_id": input.resource.subject.id})
    obj.id != ""
}
