/*
 * Copyright (c) 2015.  Gary Harpaz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ppojo;

import javafx.util.Pair;
import org.ppojo.data.*;
import org.ppojo.exceptions.DuplicateOptionsFileInFolder;
import org.ppojo.exceptions.FolderNotFoundException;
import org.ppojo.exceptions.FolderPathNotADirectory;
import org.ppojo.trace.*;
import org.ppojo.utils.Helpers;
import org.ppojo.utils.ValidationResult;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Created by GARY on 9/25/2015.
 */
public class SchemaGraphParser {

    public SchemaGraphParser(Iterable<String> rootSourceFolders, Iterable<ITemplateFileQuery> templateQueries,ArtifactOptions defaultOptions
    ,ILoggingService loggingService) {
        if (rootSourceFolders==null)
            throw new NullPointerException("rootSourceFolder");
        if (templateQueries==null)
            throw new NullPointerException("templateQueries");
        if (defaultOptions==null)
            throw new NullPointerException("defaultOptions");
        _templatesByFilePath=new HashMap<>();
        _allArtifactsByArtifactKey =new HashMap<>();
        _rootSourceFolders=rootSourceFolders;
        _templateQueries=templateQueries;
        _defaultOptions=defaultOptions;
        _loggingService =loggingService;
    }

    private final ArtifactOptions _defaultOptions;
    private Iterable<String> _rootSourceFolders;

    private Iterable<ITemplateFileQuery> _templateQueries;
    private Map<String,TemplateFileParser> _templatesByFilePath;
    private Map<String,ArtifactParser> _allArtifactsByArtifactKey;
    private boolean _throwFirstErrorException=true;
    private boolean _allParsersValid=false;
    private final ILoggingService _loggingService;




    public  void appendLineToLog(String message) {
        _loggingService.appendLine(message);
    }

    public Map<String,TemplateFileParser> getAllTemplates() {
        return _templatesByFilePath;
    }
    public Map<String,ArtifactParser> getAllArtifacts() {
        return _allArtifactsByArtifactKey;
    }
    public boolean isThrowFirstErrorException() {
        return _throwFirstErrorException;
    }
    public void setThrowFirstErrorException(boolean throwFirstErrorException) {
        _throwFirstErrorException = throwFirstErrorException;
    }
    public Iterable<ITemplateFileQuery> getTemplateQueries() {
        return _templateQueries;
    }




    public void generateArtifacts() {
        ZonedDateTime zonedDateTime = ZonedDateTime.now();
        appendLineToLog(zonedDateTime.toLocalDate().toString()+" SchemaGraphParser.generateArtifacts");
        validateAndResolveInputParams();
        deserializeTemplateFiles();
        ValidationResult validationResult=new ValidationResult(_throwFirstErrorException);
        validateTemplates(validationResult);
        validateArtifacts(validationResult);
        if (!validationResult.hasErrors()) {
            resolveArtifacts(validationResult);
        }
        if (!validationResult.hasErrors()) {
            _allParsersValid=true;
            SchemaGraph schemaGraph=generateGraph();
            schemaGraph.produceArtifactFiles();
        }
        else
            _allParsersValid=false;
    }

    public void listMatchedTemplates() {
        ZonedDateTime zonedDateTime = ZonedDateTime.now();
        appendLineToLog(zonedDateTime.toLocalDate().toString()+" SchemaGraphParser.listMatchedTemplates");
        validateAndResolveInputParams();
    }



    private SchemaGraph generateGraph() {
        assertAllValid();
        List<ArtifactFile> artifactFiles=new ArrayList<>();
        generateGraphRecursive(artifactFiles,_allArtifactsByArtifactKey.values());
        return new SchemaGraph(artifactFiles,_loggingService);

    }
    private void generateGraphRecursive(List<ArtifactFile> artifactFiles,Iterable<ArtifactParser> artifactParsers) {
        if (artifactParsers==null)
            return;
        for (ArtifactParser artifactParser : artifactParsers) {
            if (artifactParser.getArtifactBase()!=null) //artifact already created through recursion skip ahead
                continue;
            generateGraphRecursive(artifactFiles,artifactParser.getDependantArtifactsNotYetGenerated());
            ArtifactFile artifactFile=artifactParser.getArtifactFile();
            artifactFiles.add(artifactFile);
            ArtifactBase artifactBase=newArtifact(artifactParser,artifactFile);
            artifactParser.setArtifactBase(artifactBase);
        }

    }

    private void assertAllValid() {
        if (!_allParsersValid)
            throw new RuntimeException("Not all parsers are valid , graph can not be generated");
    }

    private ArtifactBase newArtifact(ArtifactParser artifactParser,IArtifactParent artifactParent) {
        ArtifactBase artifactBase=null;
        switch (artifactParser.getArtifactType()) {
            case Pojo:
                ClassArtifactData classArtifactData=(ClassArtifactData)artifactParser.getRawData();
                artifactBase=new PojoArtifact(artifactParent,artifactParser
              ,classArtifactData.extend,classArtifactData.implement);
                break;
            case Interface:
                InterfaceArtifactData interfaceArtifactData=(InterfaceArtifactData)artifactParser.getRawData();
                artifactBase=new InterfaceArtifact(artifactParent,artifactParser
                ,interfaceArtifactData.isReadOnly,interfaceArtifactData.extend);
                break;
            case FieldEnum:
                artifactBase=new FieldEnumArtifact(artifactParent,artifactParser);
                break;
            case ImmutableClass:
                ImmutableClassData immutableClassData=(ImmutableClassData)artifactParser.getRawData();
                PojoArtifact pojoArtifact=artifactParser.getImmutableDataArtifact();
                artifactBase=new ImmutableClassArtifact(artifactParent,artifactParser,immutableClassData.extend,immutableClassData.implement,pojoArtifact);
                break;
            case FluentBuilder:
                FluentBuilderData fluentBuilderData=(FluentBuilderData)artifactParser.getRawData();
                PojoArtifact fluentDataArtifact=artifactParser.getFluentDataArtifact();
                artifactBase=new FluentBuilderArtifact(artifactParent,artifactParser,fluentBuilderData.extend,fluentBuilderData.implement,fluentDataArtifact);
                break;
            default:
                throw new RuntimeException("Artifact type creation does not implement "+artifactParser.getArtifactType()+" in "+artifactParser.getRawData().name);
        }
        artifactParent.addChildArtifact(artifactBase);
        return artifactBase;
    }

    private void resolveArtifacts(ValidationResult validationResult) {

        ArrayList<ArtifactParser> unResolvedArtifacts=new ArrayList<>(_allArtifactsByArtifactKey.values());
        do {
            int prevNumUnresolved=_allArtifactsByArtifactKey.size();
            for (int i = unResolvedArtifacts.size() - 1; i >= 0; i--) {
                if (!unResolvedArtifacts.get(i).retryResolve(this,validationResult))
                    unResolvedArtifacts.remove(i);
            }
            if (unResolvedArtifacts.size()==0)
                break;
            if (prevNumUnresolved==unResolvedArtifacts.size()) {
                validationResult.addError("No progress resolving artifact dependencies. Possible cyclical dependencies stopping.");
                break;
            }
        } while (true);




    }

    private void validateArtifacts(ValidationResult validationResult) {
        for (TemplateFileParser templateFileParser : _templatesByFilePath.values()) {
            templateFileParser.validateArtifacts(this,validationResult);
        }
    }

    private void validateTemplates(ValidationResult validationResult) {
        for (TemplateFileParser templateFileParser : _templatesByFilePath.values()) {
            templateFileParser.validateTemplate(this,validationResult);
        }
    }


/*
    private void deserializeTemplateFiles() {
        Serializer serializer=new Serializer();
        for (String file : _templatesByFilePath.keySet()) {
            String json= Helpers.readTextFile(file);
            TemplateFileData rawData=serializer.deserialize(json,file);
            _templatesByFilePath.put(file,new TemplateFileParser(file,rawData,_defaultOptions));
        }
    }
    */
    private void deserializeTemplateFiles() {
        Serializer serializer=new Serializer();

        HashMap<String,Pair<String,TemplateFileData>> defaultOptionsFilesByFolder=new HashMap<>();
        HashMap<String,TemplateFileData> regularTemplatesByFileName=new HashMap<>();

        for (String file : _templatesByFilePath.keySet()) {
            String json = Helpers.readTextFile(file);
            TemplateFileData rawData = serializer.deserialize(json, file);
            if (rawData.getSchemaRelationTypes()!=TemplateSchemaRelationTypes.OptionsConfig)
                regularTemplatesByFileName.put(file,rawData);
            else
            {
                String folderPath=(new File(file)).getParent();
                Pair<String,TemplateFileData> templateFileDataPair=defaultOptionsFilesByFolder.get(folderPath);
                if (templateFileDataPair!=null)
                    throw new DuplicateOptionsFileInFolder("Duplicate option files are located in folder "+folderPath+
                            " this is not allowed. files are "+templateFileDataPair.getKey()+" and "+file);
                templateFileDataPair=new Pair<>(file,rawData);
                defaultOptionsFilesByFolder.put(folderPath,templateFileDataPair);
            }
        }
        ArrayList<Pair<String,TemplateFileData>> pairArrayList=new ArrayList<>(defaultOptionsFilesByFolder.values());
        while (pairArrayList.size()>0) {
            int prev_size=pairArrayList.size();
            for (int i=pairArrayList.size()-1; i>=0; i--) {
                Pair<String,TemplateFileData> pair=pairArrayList.get(i);
                String fileName=pair.getKey();
                TemplateFileData rawData=pair.getValue();
                String searchChildPath=Paths.get(fileName).getParent().toString();
                if (newTemplateFileParser(searchChildPath,fileName,rawData,defaultOptionsFilesByFolder)) {
                    pairArrayList.remove(i);
                }
            }
            if (prev_size==pairArrayList.size())
                throw new RuntimeException("Invalid state in deserializeTemplateFiles. Can not resolve tree dependency of options");
        }

        regularTemplatesByFileName.forEach((k,v)->{
            newTemplateFileParser(k,k,v,defaultOptionsFilesByFolder);
        });
    }
    private boolean newTemplateFileParser(String searchChildPath,String fileName,TemplateFileData rawData,
                                          HashMap<String,Pair<String,TemplateFileData>> defaultOptionsFilesByFolder) {
        String parentOptionsFolder=getClosestParentOptionFolder(searchChildPath,defaultOptionsFilesByFolder.keySet());
        ArtifactOptions parentOptions=null;
        if (Helpers.IsNullOrEmpty(parentOptionsFolder))
            parentOptions=_defaultOptions;
        else {
            Pair<String,TemplateFileData> parentOptionsFile=defaultOptionsFilesByFolder.get(parentOptionsFolder);
            TemplateFileParser parentParser=_templatesByFilePath.get(parentOptionsFile.getKey());
            if (parentParser!=null) //else it might get created in the next while iteration
                parentOptions=parentParser.getOptions();
        }
        if (parentOptions!=null) {
            _templatesByFilePath.put(fileName,new TemplateFileParser(fileName,rawData,parentOptions));
            return true;
        }
        return false;
    }

    private String getClosestParentOptionFolder(String searchChildPath, Iterable<String> paths) {
        String match=null;
        ArrayList<String> matches=new ArrayList<>();
        for (String path : paths) {
            if (!searchChildPath.equals(path) && searchChildPath.startsWith(path))
                matches.add(path);
        }
        if (matches.size()>0) {
            match=matches.stream().max((x, y) -> ((Integer)x.length()).compareTo(y.length())).get();
        }
        return match;
    }

    public void validateAndResolveInputParams() {
        ArrayList<String> normalizedSourceFolders=new ArrayList<>();
        for (String rootSourceFolder : _rootSourceFolders) {
            Path path=Paths.get(rootSourceFolder).normalize();
            if (!path.isAbsolute()) {
                path=path.toAbsolutePath();
            }
            File file=path.toFile();
            if (!file.exists())
                throw new FolderNotFoundException("Sources folder "+file.toString()+" does not exist");
            if (!file.isDirectory())
              throw new FolderPathNotADirectory("Sources folder "+file.toString()+" is not a directory");
            String pathStr=path.toString();
            normalizedSourceFolders.add(pathStr);
            addTraceEvent(new ValidatedSourceFolderPath(pathStr));
        }
        _rootSourceFolders=normalizedSourceFolders;
        if (normalizedSourceFolders.size()==0)
            throw new IllegalArgumentException("No sources folder provided. Must specify at least one sources folder.");
        addTraceEvent(new ValidatedSourceFolders(normalizedSourceFolders.size()));
        for (ITemplateFileQuery templateQuery : _templateQueries) {
            addTraceEvent(new ExecutingTemplateQuery(templateQuery));
            for (String file : templateQuery.getTemplateFiles()) {
                boolean isDuplicate=_templatesByFilePath.containsKey(file);
                _templatesByFilePath.put(file,null);
                addTraceEvent(new QueryTemplateFileMatch(file,isDuplicate));
            }
        }
        addTraceEvent(new ExecutedTemplateQueries(_templatesByFilePath.size()));
    }

    private void addTraceEvent(ITraceEvent event) {
        _loggingService.addTraceEvent(event);
    }

    public Iterable<String> getRootSourceFolders() {
        return _rootSourceFolders;
    }

}
