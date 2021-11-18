/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2021 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.serving.service;

import com.google.protobuf.ByteString;
import feast.common.models.FeatureV2;
import feast.proto.core.DataSourceProto;
import feast.proto.core.FeatureProto;
import feast.proto.core.FeatureViewProto;
import feast.proto.core.OnDemandFeatureViewProto;
import feast.proto.serving.ServingAPIProto;
import feast.proto.serving.TransformationServiceAPIProto.TransformFeaturesRequest;
import feast.proto.serving.TransformationServiceAPIProto.TransformFeaturesResponse;
import feast.proto.serving.TransformationServiceAPIProto.ValueType;
import feast.proto.serving.TransformationServiceGrpc;
import feast.proto.types.ValueProto;
import feast.serving.registry.RegistryRepository;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.*;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.ByteArrayReadableSeekableByteChannel;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tomcat.util.http.fileupload.ByteArrayOutputStream;
import org.slf4j.Logger;

public class OnlineTransformationService implements TransformationService {

  private static final Logger log =
      org.slf4j.LoggerFactory.getLogger(OnlineTransformationService.class);
  private final TransformationServiceGrpc.TransformationServiceBlockingStub stub;
  private final RegistryRepository registryRepository;
  static final int INT64_BITWIDTH = 64;
  static final int INT32_BITWIDTH = 32;

  public OnlineTransformationService(
      String transformationServiceEndpoint, RegistryRepository registryRepository) {
    if (transformationServiceEndpoint != null) {
      final ManagedChannel channel =
          ManagedChannelBuilder.forTarget(transformationServiceEndpoint).usePlaintext().build();
      this.stub = TransformationServiceGrpc.newBlockingStub(channel);
    } else {
      this.stub = null;
    }
    this.registryRepository = registryRepository;
  }

  /** {@inheritDoc} */
  @Override
  public TransformFeaturesResponse transformFeatures(
      TransformFeaturesRequest transformFeaturesRequest) {
    return this.stub.transformFeatures(transformFeaturesRequest);
  }

  /** {@inheritDoc} */
  @Override
  public Pair<Set<String>, List<ServingAPIProto.FeatureReferenceV2>>
      extractRequestDataFeatureNamesAndOnDemandFeatureInputs(
          List<ServingAPIProto.FeatureReferenceV2> onDemandFeatureReferences, String projectName) {
    Set<String> requestDataFeatureNames = new HashSet<String>();
    List<ServingAPIProto.FeatureReferenceV2> onDemandFeatureInputs =
        new ArrayList<ServingAPIProto.FeatureReferenceV2>();
    for (ServingAPIProto.FeatureReferenceV2 featureReference : onDemandFeatureReferences) {
      OnDemandFeatureViewProto.OnDemandFeatureViewSpec onDemandFeatureViewSpec =
          this.registryRepository.getOnDemandFeatureViewSpec(projectName, featureReference);
      Map<String, OnDemandFeatureViewProto.OnDemandInput> inputs =
          onDemandFeatureViewSpec.getInputsMap();

      for (OnDemandFeatureViewProto.OnDemandInput input : inputs.values()) {
        OnDemandFeatureViewProto.OnDemandInput.InputCase inputCase = input.getInputCase();
        switch (inputCase) {
          case REQUEST_DATA_SOURCE:
            DataSourceProto.DataSource requestDataSource = input.getRequestDataSource();
            DataSourceProto.DataSource.RequestDataOptions requestDataOptions =
                requestDataSource.getRequestDataOptions();
            Set<String> requestDataNames = requestDataOptions.getSchemaMap().keySet();
            requestDataFeatureNames.addAll(requestDataNames);
            break;
          case FEATURE_VIEW:
            FeatureViewProto.FeatureView featureView = input.getFeatureView();
            FeatureViewProto.FeatureViewSpec featureViewSpec = featureView.getSpec();
            String featureViewName = featureViewSpec.getName();
            for (FeatureProto.FeatureSpecV2 featureSpec : featureViewSpec.getFeaturesList()) {
              String featureName = featureSpec.getName();
              ServingAPIProto.FeatureReferenceV2 onDemandFeatureInput =
                  ServingAPIProto.FeatureReferenceV2.newBuilder()
                      .setFeatureTable(featureViewName)
                      .setName(featureName)
                      .build();
              onDemandFeatureInputs.add(onDemandFeatureInput);
            }
            break;
          default:
            throw Status.INTERNAL
                .withDescription(
                    "OnDemandInput proto input field has an unexpected type: " + inputCase)
                .asRuntimeException();
        }
      }
    }
    Pair<Set<String>, List<ServingAPIProto.FeatureReferenceV2>> pair =
        new ImmutablePair<Set<String>, List<ServingAPIProto.FeatureReferenceV2>>(
            requestDataFeatureNames, onDemandFeatureInputs);
    return pair;
  }

  /** {@inheritDoc} */
  public Pair<
          List<ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow>,
          Map<String, List<ValueProto.Value>>>
      separateEntityRows(
          Set<String> requestDataFeatureNames, ServingAPIProto.GetOnlineFeaturesRequestV2 request) {
    // Separate entity rows into entity data and request feature data.
    List<ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow> entityRows =
        new ArrayList<ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow>();
    Map<String, List<ValueProto.Value>> requestDataFeatures =
        new HashMap<String, List<ValueProto.Value>>();

    for (ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow entityRow :
        request.getEntityRowsList()) {
      Map<String, ValueProto.Value> fieldsMap = new HashMap<String, ValueProto.Value>();

      for (Map.Entry<String, ValueProto.Value> entry : entityRow.getFieldsMap().entrySet()) {
        String key = entry.getKey();
        ValueProto.Value value = entry.getValue();

        if (requestDataFeatureNames.contains(key)) {
          if (!requestDataFeatures.containsKey(key)) {
            requestDataFeatures.put(key, new ArrayList<ValueProto.Value>());
          }
          requestDataFeatures.get(key).add(value);
        } else {
          fieldsMap.put(key, value);
        }
      }

      // Construct new entity row containing the extracted entity data, if necessary.
      if (!fieldsMap.isEmpty()) {
        ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow newEntityRow =
            ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow.newBuilder()
                .setTimestamp(entityRow.getTimestamp())
                .putAllFields(fieldsMap)
                .build();
        entityRows.add(newEntityRow);
      }
    }

    Pair<
            List<ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow>,
            Map<String, List<ValueProto.Value>>>
        pair =
            new ImmutablePair<
                List<ServingAPIProto.GetOnlineFeaturesRequestV2.EntityRow>,
                Map<String, List<ValueProto.Value>>>(entityRows, requestDataFeatures);
    return pair;
  }

  /** {@inheritDoc} */
  public void processTransformFeaturesResponse(
      feast.proto.serving.TransformationServiceAPIProto.TransformFeaturesResponse
          transformFeaturesResponse,
      String onDemandFeatureViewName,
      Set<String> onDemandFeatureStringReferences,
      List<Map<String, ValueProto.Value>> values,
      List<Map<String, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus>> statuses) {
    try {
      BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
      ArrowFileReader reader =
          new ArrowFileReader(
              new ByteArrayReadableSeekableByteChannel(
                  transformFeaturesResponse
                      .getTransformationOutput()
                      .getArrowValue()
                      .toByteArray()),
              allocator);
      reader.loadNextBatch();
      VectorSchemaRoot readBatch = reader.getVectorSchemaRoot();
      Schema responseSchema = readBatch.getSchema();
      List<Field> responseFields = responseSchema.getFields();

      for (Field field : responseFields) {
        String columnName = field.getName();
        String fullFeatureName = onDemandFeatureViewName + ":" + columnName;
        ArrowType columnType = field.getType();

        // The response will contain all features for the specified ODFV, so we
        // skip the features that were not requested.
        if (!onDemandFeatureStringReferences.contains(fullFeatureName)) {
          continue;
        }

        FieldVector fieldVector = readBatch.getVector(field);
        int valueCount = fieldVector.getValueCount();

        // TODO: support all Feast types
        // TODO: clean up the switch statement
        if (columnType instanceof ArrowType.Int) {
          int bitWidth = ((ArrowType.Int) columnType).getBitWidth();
          switch (bitWidth) {
            case INT64_BITWIDTH:
              for (int i = 0; i < valueCount; i++) {
                long int64Value = ((BigIntVector) fieldVector).get(i);
                Map<String, ValueProto.Value> rowValues = values.get(i);
                Map<String, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus> rowStatuses =
                    statuses.get(i);
                ValueProto.Value value =
                    ValueProto.Value.newBuilder().setInt64Val(int64Value).build();
                rowValues.put(fullFeatureName, value);
                rowStatuses.put(
                    fullFeatureName, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus.PRESENT);
              }
              break;
            case INT32_BITWIDTH:
              for (int i = 0; i < valueCount; i++) {
                int intValue = ((IntVector) fieldVector).get(i);
                Map<String, ValueProto.Value> rowValues = values.get(i);
                Map<String, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus> rowStatuses =
                    statuses.get(i);
                ValueProto.Value value =
                    ValueProto.Value.newBuilder().setInt32Val(intValue).build();
                rowValues.put(fullFeatureName, value);
                rowStatuses.put(
                    fullFeatureName, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus.PRESENT);
              }
              break;
            default:
              throw Status.INTERNAL
                  .withDescription(
                      "Column "
                          + columnName
                          + " is of type ArrowType.Int but has bitWidth "
                          + bitWidth
                          + " which cannot be handled.")
                  .asRuntimeException();
          }
        } else if (columnType instanceof ArrowType.FloatingPoint) {
          FloatingPointPrecision precision = ((ArrowType.FloatingPoint) columnType).getPrecision();
          switch (precision) {
            case DOUBLE:
              for (int i = 0; i < valueCount; i++) {
                double doubleValue = ((Float8Vector) fieldVector).get(i);
                Map<String, ValueProto.Value> rowValues = values.get(i);
                Map<String, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus> rowStatuses =
                    statuses.get(i);
                ValueProto.Value value =
                    ValueProto.Value.newBuilder().setDoubleVal(doubleValue).build();
                rowValues.put(fullFeatureName, value);
                rowStatuses.put(
                    fullFeatureName, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus.PRESENT);
              }
              break;
            case SINGLE:
              for (int i = 0; i < valueCount; i++) {
                float floatValue = ((Float4Vector) fieldVector).get(i);
                Map<String, ValueProto.Value> rowValues = values.get(i);
                Map<String, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus> rowStatuses =
                    statuses.get(i);
                ValueProto.Value value =
                    ValueProto.Value.newBuilder().setFloatVal(floatValue).build();
                rowValues.put(fullFeatureName, value);
                rowStatuses.put(
                    fullFeatureName, ServingAPIProto.GetOnlineFeaturesResponse.FieldStatus.PRESENT);
              }
              break;
            default:
              throw Status.INTERNAL
                  .withDescription(
                      "Column "
                          + columnName
                          + " is of type ArrowType.FloatingPoint but has precision "
                          + precision
                          + " which cannot be handled.")
                  .asRuntimeException();
          }
        }
      }
    } catch (IOException e) {
      log.info(e.toString());
      throw Status.INTERNAL
          .withDescription(
              "Unable to correctly process transform features response: " + e.toString())
          .asRuntimeException();
    }
  }

  /** {@inheritDoc} */
  public ValueType serializeValuesIntoArrowIPC(List<Map<String, ValueProto.Value>> values) {
    // In order to be serialized correctly, the data must be packaged in a VectorSchemaRoot.
    // We first construct all the columns.
    Map<String, FieldVector> columnNameToColumn = new HashMap<String, FieldVector>();
    BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
    Map<String, ValueProto.Value> firstAugmentedRowValues = values.get(0);
    for (Map.Entry<String, ValueProto.Value> entry : firstAugmentedRowValues.entrySet()) {
      // The Python FTS does not expect full feature names, so we extract the feature name.
      String columnName = FeatureV2.getFeatureName(entry.getKey());
      ValueProto.Value.ValCase valCase = entry.getValue().getValCase();
      FieldVector column;
      // TODO: support all Feast types
      switch (valCase) {
        case INT32_VAL:
          column = new IntVector(columnName, allocator);
          break;
        case INT64_VAL:
          column = new BigIntVector(columnName, allocator);
          break;
        case DOUBLE_VAL:
          column = new Float8Vector(columnName, allocator);
          break;
        case FLOAT_VAL:
          column = new Float4Vector(columnName, allocator);
          break;
        default:
          throw Status.INTERNAL
              .withDescription(
                  "Column " + columnName + " has a type that is currently not handled: " + valCase)
              .asRuntimeException();
      }
      column.allocateNew();
      columnNameToColumn.put(columnName, column);
    }

    // Add the data, row by row.
    for (int i = 0; i < values.size(); i++) {
      Map<String, ValueProto.Value> augmentedRowValues = values.get(i);

      for (Map.Entry<String, ValueProto.Value> entry : augmentedRowValues.entrySet()) {
        String columnName = FeatureV2.getFeatureName(entry.getKey());
        ValueProto.Value value = entry.getValue();
        ValueProto.Value.ValCase valCase = value.getValCase();
        FieldVector column = columnNameToColumn.get(columnName);
        // TODO: support all Feast types
        switch (valCase) {
          case INT32_VAL:
            ((IntVector) column).setSafe(i, value.getInt32Val());
            break;
          case INT64_VAL:
            ((BigIntVector) column).setSafe(i, value.getInt64Val());
            break;
          case DOUBLE_VAL:
            ((Float8Vector) column).setSafe(i, value.getDoubleVal());
            break;
          case FLOAT_VAL:
            ((Float4Vector) column).setSafe(i, value.getFloatVal());
            break;
          default:
            throw Status.INTERNAL
                .withDescription(
                    "Column "
                        + columnName
                        + " has a type that is currently not handled: "
                        + valCase)
                .asRuntimeException();
        }
      }
    }

    // Construct the VectorSchemaRoot.
    List<Field> columnFields = new ArrayList<Field>();
    List<FieldVector> columns = new ArrayList<FieldVector>();
    for (FieldVector column : columnNameToColumn.values()) {
      column.setValueCount(values.size());
      columnFields.add(column.getField());
      columns.add(column);
    }
    VectorSchemaRoot schemaRoot = new VectorSchemaRoot(columnFields, columns);

    // Serialize the VectorSchemaRoot into Arrow IPC format.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ArrowFileWriter writer = new ArrowFileWriter(schemaRoot, null, Channels.newChannel(out));
    try {
      writer.start();
      writer.writeBatch();
      writer.end();
    } catch (IOException e) {
      log.info(e.toString());
      throw Status.INTERNAL
          .withDescription(
              "ArrowFileWriter could not write properly; failed with error: " + e.toString())
          .asRuntimeException();
    }
    byte[] byteData = out.toByteArray();
    ByteString inputData = ByteString.copyFrom(byteData);
    ValueType transformationInput = ValueType.newBuilder().setArrowValue(inputData).build();
    return transformationInput;
  }
}
