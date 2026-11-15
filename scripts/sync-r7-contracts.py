#!/usr/bin/env python3
"""Sync/generate only the independent R7 packs; --check never writes."""
import argparse
import hashlib
import json
import re
from collections import OrderedDict
from copy import deepcopy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'core/data/src/main'

def camel(value):
    first,*tail=value.split('_')
    return first+''.join(part[:1].upper()+part[1:] for part in tail)
def pascal(value): return ''.join(part[:1].upper()+part[1:] for part in re.split(r'[-_]',value))
def quote(value): return json.dumps(value,ensure_ascii=False)

def generate(bundle):
    directory=BASE/f'assets/{bundle}-contracts'
    filename=f'{bundle}.schema.json'
    manifest=json.loads((directory/'manifest.json').read_text())
    expected={filename,f'{bundle}.openapi.json'}|({'review2.schema.json','review3.schema.json'} if bundle=='next-observation' else set())
    assert set(manifest)==expected
    for name,digest in manifest.items():
        assert hashlib.sha256((directory/name).read_bytes()).hexdigest()==digest
    # Check every local JSON pointer, including OpenAPI-only references which
    # need not be reached while generating Kotlin DTOs. Never retrieve remotely.
    documents={name:json.loads((directory/name).read_text()) for name in manifest}
    def check_refs(value,current):
        if isinstance(value,dict):
            if '$ref' in value:
                target,mark,pointer=value['$ref'].partition('#');target=target or current
                assert mark and target in documents and pointer.startswith('/'),value['$ref']
                resolved=documents[target]
                for token in pointer[1:].split('/'):
                    token=token.replace('~1','/').replace('~0','~')
                    resolved=resolved[int(token)] if isinstance(resolved,list) else resolved[token]
            for child in value.values():check_refs(child,current)
        elif isinstance(value,list):
            for child in value:check_refs(child,current)
    for name,document in documents.items():check_refs(document,name)
    doc=json.loads((directory/filename).read_text())
    if bundle=='next-observation':
        # Reuse original Review2/3 DTOs: verify exact origins, never flatten or
        # regenerate an observer-sized duplicate model graph.
        origins={}
        for origin in ('review2','review3'):
            name=f'{origin}.schema.json';source=BASE/f'assets/{origin}-contracts'
            source_manifest=json.loads((source/'manifest.json').read_text())
            assert set(source_manifest)=={name,f'{origin}.openapi.json'}
            for file,digest in source_manifest.items():assert hashlib.sha256((source/file).read_bytes()).hexdigest()==digest
            raw=(directory/name).read_bytes();assert raw==(source/name).read_bytes(),'Conflicting frozen observation origin'
            origins[name]=hashlib.sha256(raw).hexdigest()
        assert doc['x-frozen-origin-sha256']==origins
        return None,None
    prefix='Wire'+pascal(bundle)
    models=OrderedDict((prefix+name,value) for name,value in doc['$defs'].items())
    # Inline union branches retain strict JSON validation and gain named DTOs.
    for name,value in list(models.items()):
        if 'oneOf' not in value:continue
        children=[]
        for index,child in enumerate(value['oneOf']):
            if '$ref' in child:children.append(child);continue
            leaf=name.removeprefix(prefix)+'Variant'+str(index+1)
            doc['$defs'][leaf]=child;models[prefix+leaf]=child
            children.append({'$ref':'#/$defs/'+leaf})
        value['oneOf']=children
    def resolve(reference):
        target,pointer=reference.split('#',1)
        assert target in {'',filename}, reference
        name=pointer.rsplit('/',1)[1]
        assert pointer.startswith('/$defs/') and name in doc['$defs'],reference
        return prefix+name,doc['$defs'][name]
    def shape(value):
        value=deepcopy(value)
        if '$ref' in value:
            _,base=resolve(value.pop('$ref'));value=deepcopy(base)|value
        for part in value.pop('allOf',[]):
            if '$ref' not in part and 'properties' not in part: continue
            part=shape(part)
            value.setdefault('type',part.get('type','object'))
            value['properties']=part.get('properties',{})|value.get('properties',{})
            value['required']=list(dict.fromkeys(part.get('required',[])+value.get('required',[])))
        return value
    parents={}
    for name,value in models.items():
        for child in value.get('oneOf',[]):
            child_name,_=resolve(child['$ref']);parents.setdefault(child_name,[]).append(name)
    def typed(value,proposed):
        if '$ref' in value:return resolve(value['$ref'])[0]
        if 'anyOf' in value:
            nonnull=[v for v in value['anyOf'] if v.get('type')!='null']
            assert len(nonnull)==1 and len(value['anyOf'])==2
            return typed(nonnull[0],proposed)+'?'
        if 'allOf' in value:
            refs=[v for v in value['allOf'] if '$ref' in v]
            if refs:return resolve(refs[0]['$ref'])[0]
        kind=value.get('type')
        if kind=='object':
            models.setdefault(proposed,value);return proposed
        if kind=='array':return 'List<'+typed(value['items'],proposed+'Item')+'>'
        return {'string':'String','integer':'Long','boolean':'Boolean','null':'JsonElement'}[kind]
    out=['// Generated by scripts/sync-r7-contracts.py; edit R7 schema, then regenerate.',
         'package io.github.driveenglishlab.pleos.data.wire','','import kotlinx.serialization.*',
         'import kotlinx.serialization.json.*','import kotlinx.serialization.modules.*','']
    processed=set()
    while len(processed)<len(models):
        name=next(n for n in models if n not in processed);original=models[name];processed.add(name)
        if '$ref' in original and set(original)<={'$ref','description'}:
            out.append(f'typealias {name} = {resolve(original["$ref"])[0]}\n');continue
        if 'oneOf' in original:
            out += [f'@Serializable(with = {name}Serializer::class)',f'sealed interface {name}',
                f'object {name}Serializer : JsonContentPolymorphicSerializer<{name}>({name}::class) {{',
                f'    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<{name}> {{',
                '        val fields = element.jsonObject', '        return when {']
            for child in original['oneOf']:
                child_name,child_shape=resolve(child['$ref']);child_shape=shape(child_shape)
                fields=child_shape['properties']
                discriminators=[(k,v['const']) for k,v in fields.items() if 'const' in v]
                if original.get('x-content-discriminator')=='content_ref.source_kind':
                    present='fields["content_ref"]?.jsonObject?.containsKey("source_kind") == true'
                    conditions=[present if child_name.endswith('AssessmentFeedback6') else '!('+present+')']
                elif discriminators:
                    conditions=['fields['+quote(k)+'] == JsonPrimitive('+quote(v)+')' for k,v in discriminators]
                else:
                    required=child_shape.get('required',[])
                    conditions=['fields.keys.containsAll(setOf<String>('+', '.join(quote(k) for k in required)+'))',
                                'fields.keys.all { it in setOf<String>('+', '.join(quote(k) for k in fields)+') }']
                    for key,field in fields.items():
                        if field.get('type')=='null':conditions.append('fields['+quote(key)+'] === JsonNull')
                        elif field.get('type') in {'string','integer','boolean'} and key in required:
                            conditions.append('fields['+quote(key)+'] !== JsonNull')
                out.append('            '+' && '.join(conditions)+' -> '+child_name+'.serializer()')
            out += ['            else -> throw SerializationException("Unknown additive record kind.")','        }','    }','}',''];continue
        value=shape(original)
        if value.get('type')=='string':
            if 'enum' in value:
                out += ['@Serializable',f'enum class {name} {{']
                out += [f'    @SerialName({quote(v)}) {v.upper()},' for v in value['enum']]
                out += ['}','']
            else:out += [f'typealias {name} = String','']
            continue
        assert value.get('type')=='object',(name,value)
        properties=value.get('properties',{})
        if not properties:
            out += ['@Serializable',f'class {name}'+(' : '+', '.join(parents[name]) if name in parents else ''),''];continue
        out += ['@Serializable',f'data class {name}(']
        for key,field in properties.items():
            kt=typed(field,name+pascal(camel(key)));optional=key not in value.get('required',[])
            if optional and not kt.endswith('?'):kt+='?'
            out.append(f'    @SerialName({quote(key)}) val {camel(key)}: {kt}'+(' = null' if optional else '')+',')
        out += [')'+(' : '+', '.join(parents[name]) if name in parents else ''),'']
    module=camel(bundle.replace('-','_'))
    out += [f'val {module}WireSerializers = SerializersModule {{']
    for name,value in models.items():
        if 'oneOf' not in value:continue
        out.append(f'    polymorphic({name}::class) {{')
        for child in value['oneOf']:
            child_name,_=resolve(child['$ref']);out.append(f'        subclass({child_name}::class, {child_name}.serializer())')
        out.append('    }')
    out += ['}','']
    return BASE/f'kotlin/io/github/driveenglishlab/pleos/data/wire/Generated{pascal(bundle)}Wire.kt','\n'.join(out)

parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');parser.add_argument('--bundle',choices=('assessment-review','catalog-views','review2','review3','next-observation'));args=parser.parse_args()
server_base=ROOT.parent/'pleos-ingestion/ingestion/contracts'
if not args.check and not server_base.is_dir():raise SystemExit('Reviewed server inputs are required to update contract snapshots.')
for bundle in ([args.bundle] if args.bundle else ('assessment-review','catalog-views','review2','review3','next-observation')):
    server=server_base/f'{bundle}-contracts'
    target=BASE/f'assets/{bundle}-contracts'
    names=(f'{bundle}.schema.json',f'{bundle}.openapi.json','manifest.json')+(('review2.schema.json','review3.schema.json') if bundle=='next-observation' else ())
    for name in names:
        path=target/name
        if server_base.is_dir():
            raw=(server/name).read_bytes()
            if args.check:assert path.is_file() and path.read_bytes()==raw,f'Outdated R7 snapshot: {path}'
            else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
        else:
            # Independent app CI validates its shipped assets/manifest/DTOs.
            # This does not claim comparison with another repository's checkout.
            assert args.check and path.is_file(),f'Missing bundled contract: {path}'
    path,source=generate(bundle)
    if path is None:continue
    if args.check:assert path.is_file() and path.read_text()==source,f'Outdated additive DTO: {path.name}'
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
print(('Checked' if args.check else 'Generated')+' independent R7 snapshots and Kotlin DTOs.'+(' Bundled-only check; no cross-repository comparison.' if not server_base.is_dir() else ' Server snapshot comparison included.'))
