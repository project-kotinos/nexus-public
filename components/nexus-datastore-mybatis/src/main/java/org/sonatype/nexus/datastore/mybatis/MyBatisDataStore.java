/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.datastore.mybatis;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.EntityUUID;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.thread.TcclBlock;
import org.sonatype.nexus.crypto.PbeCipherFactory.PbeCipher;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.crypto.internal.MavenCipherImpl;
import org.sonatype.nexus.datastore.DataStoreSupport;
import org.sonatype.nexus.datastore.api.DataAccess;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.Expects;
import org.sonatype.nexus.datastore.api.SchemaTemplate;
import org.sonatype.nexus.datastore.mybatis.handlers.AttributesTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.ContentTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.DateTimeTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.EncryptedStringTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.EntityUUIDTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.LenientUUIDTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.ListTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.MapTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.NestedAttributesMapTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.PasswordCharacterArrayTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.PrincipalCollectionTypeHandler;
import org.sonatype.nexus.datastore.mybatis.handlers.SetTypeHandler;
import org.sonatype.nexus.security.PasswordHelper;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Splitter.MapSplitter;
import com.google.common.io.Resources;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.factory.DefaultObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.eclipse.sisu.BeanEntry;
import org.eclipse.sisu.Mediator;
import org.eclipse.sisu.inject.BeanLocator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Splitter.onPattern;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newInputStream;
import static java.util.Arrays.asList;
import static java.util.regex.Pattern.DOTALL;
import static java.util.regex.Pattern.compile;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;
import static org.sonatype.nexus.common.text.Strings2.isBlank;
import static org.sonatype.nexus.common.text.Strings2.lower;
import static org.sonatype.nexus.common.thread.TcclBlock.begin;
import static org.sonatype.nexus.datastore.api.DataStoreManager.CONFIG_DATASTORE_NAME;
import static org.sonatype.nexus.datastore.mybatis.MyBatisDataStoreDescriptor.ADVANCED;
import static org.sonatype.nexus.datastore.mybatis.MyBatisDataStoreDescriptor.JDBC_URL;
import static org.sonatype.nexus.datastore.mybatis.MyBatisDataStoreDescriptor.SCHEMA;
import static org.sonatype.nexus.datastore.mybatis.PlaceholderTypes.configurePlaceholderTypes;
import static org.sonatype.nexus.datastore.mybatis.SensitiveAttributes.buildSensitiveAttributeFilter;
import static org.sonatype.nexus.security.PhraseService.LEGACY_PHRASE_SERVICE;

/**
 * MyBatis {@link DataStore}.
 *
 * @since 3.19
 */
@Named(MyBatisDataStoreDescriptor.NAME)
@SuppressWarnings("rawtypes")
public class MyBatisDataStore
    extends DataStoreSupport<MyBatisDataSession>
{
  private static final String REGISTERED_MESSAGE = "Registered {}";

  private static final Key<TypeHandler> TYPE_HANDLER_KEY = Key.get(TypeHandler.class);

  private static final TypeHandlerMediator CONFIG_TYPE_HANDLER_MEDIATOR = new TypeHandlerMediator(false);

  private static final TypeHandlerMediator CONTENT_TYPE_HANDLER_MEDIATOR = new TypeHandlerMediator(true);

  private static final Splitter BY_LINE = onPattern("\\r?\\n").trimResults().omitEmptyStrings();

  private static final Splitter KEY_VALUE = onPattern("[=:]").limit(2).trimResults().omitEmptyStrings();

  private static final MapSplitter TO_MAP = BY_LINE.withKeyValueSeparator(KEY_VALUE);

  private static final Pattern MAPPER_BODY = compile(".*<mapper[^>]*>(.*)</mapper>", DOTALL);

  private final Set<Class<?>> accessTypes = new HashSet<>();

  private final AtomicBoolean frozenMarker = new AtomicBoolean();

  private final PbeCipher databaseCipher;

  private final PasswordHelper passwordHelper;

  private final ApplicationDirectories directories;

  private final BeanLocator beanLocator;

  private HikariDataSource dataSource;

  private SqlSessionFactory sessionFactory;

  @Nullable
  private Predicate<String> sensitiveAttributeFilter;

  @Inject
  public MyBatisDataStore(@Named("mybatis") final PbeCipher databaseCipher,
                          final PasswordHelper passwordHelper,
                          final ApplicationDirectories directories,
                          final BeanLocator beanLocator)
  {
    checkState(databaseCipher instanceof MyBatisCipher);
    this.databaseCipher = checkNotNull(databaseCipher);
    this.passwordHelper = checkNotNull(passwordHelper);
    this.directories = checkNotNull(directories);
    this.beanLocator = checkNotNull(beanLocator);
  }

  @VisibleForTesting
  public MyBatisDataStore() {
    try {
      // use static configuration for testing
      this.databaseCipher = new MyBatisCipher();
      MavenCipherImpl passwordCipher = new MavenCipherImpl(new CryptoHelperImpl());
      this.passwordHelper = new PasswordHelper(passwordCipher, LEGACY_PHRASE_SERVICE);
    }
    catch (Exception e) {
      throw new IllegalStateException("Unexpected error during setup", e);
    }
    this.directories = null;
    this.beanLocator = null;
  }

  @Override
  protected void doStart(final String storeName, final Map<String, String> attributes) throws Exception {
    boolean isContentStore = !CONFIG_DATASTORE_NAME.equalsIgnoreCase(storeName);

    dataSource = new HikariDataSource(configureHikari(storeName, attributes));
    Environment environment = new Environment(storeName, new JdbcTransactionFactory(), dataSource);
    sessionFactory = new DefaultSqlSessionFactory(configureMyBatis(environment));

    registerCommonTypeHandlers(isContentStore);

    if (beanLocator != null) {
      // register the appropriate type handlers with the store
      beanLocator.watch(TYPE_HANDLER_KEY,
          isContentStore ? CONTENT_TYPE_HANDLER_MEDIATOR : CONFIG_TYPE_HANDLER_MEDIATOR, this);
    }
  }

  @Override
  protected void doStop() throws Exception {
    sessionFactory = null;
    accessTypes.clear();
    try {
      dataSource.close();
    }
    finally {
      dataSource = null;
    }
  }

  @Override
  public void register(final Class<? extends DataAccess> accessType) {
    if (!accessTypes.add(accessType) || accessType.isAnnotationPresent(SchemaTemplate.class)) {
      return; // skip registration if we've already seen this type or this is a template
    }

    registerSimpleAliases(accessType);
    registerDataAccessMapper(accessType);

    // finally create the schema for this DAO
    info("Creating schema for {}", accessType.getSimpleName());
    try (SqlSession session = sessionFactory.openSession()) {
      session.getMapper(accessType).createSchema();
      session.commit();
    }
  }

  @Override
  public void unregister(Class<? extends DataAccess> accessType) {
    // unregistration of custom access types is not supported
  }

  @Guarded(by = STARTED)
  @Override
  public MyBatisDataSession openSession() {
    return new MyBatisDataSession(sessionFactory.openSession());
  }

  @Guarded(by = STARTED)
  @Override
  public Connection openConnection() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  public void freeze() {
    frozenMarker.set(true);
  }

  @Override
  public void unfreeze() {
    frozenMarker.set(false);
  }

  @Override
  public boolean isFrozen() {
    return frozenMarker.get();
  }

  @Guarded(by = STARTED)
  @Override
  public void backup(final String location) throws SQLException {
    try (Connection conn = openConnection()) {
      if ("H2".equals(conn.getMetaData().getDatabaseProductName())) {
        try (PreparedStatement backupStmt = conn.prepareStatement("BACKUP TO ?")) {
          backupStmt.setString(1, location);
          backupStmt.execute();
        }
      }
      else {
        throw new UnsupportedOperationException("The underlying database is not supported for backup.");
      }
    }
  }

  /**
   * Supplies the populated Hikari configuration for this store.
   */
  private HikariConfig configureHikari(final String storeName, final Map<String, String> attributes) {
    Properties properties = new Properties();
    properties.put("poolName", storeName);
    properties.putAll(attributes);

    if (attributes.get(JDBC_URL).startsWith("jdbc:postgresql")) {
      properties.put("driverClassName", "org.postgresql.Driver");
      // workaround https://github.com/pgjdbc/pgjdbc/issues/265
      properties.put("dataSource.stringtype", "unspecified");
    }

    // Parse and unflatten advanced attributes
    Object advanced = properties.remove(ADVANCED);
    if (advanced instanceof String) {
      TO_MAP.split((String) advanced).forEach(properties::putIfAbsent);
    }

    // Hikari doesn't like blank schemas in its config
    if (isBlank(properties.getProperty(SCHEMA))) {
      properties.remove(SCHEMA);
    }

    return new HikariConfig(properties);
  }

  /**
   * Supplies the populated MyBatis configuration for this store.
   */
  private Configuration configureMyBatis(final Environment environment) throws IOException {
    Configuration myBatisConfig = loadMyBatisConfiguration(environment);

    String databaseId = new VendorDatabaseIdProvider().getDatabaseId(environment.getDataSource());
    info("MyBatis databaseId: {}", databaseId);

    // configuration elements that must always be applied
    myBatisConfig.setEnvironment(environment);
    myBatisConfig.setDatabaseId(databaseId);
    myBatisConfig.setMapUnderscoreToCamelCase(true);
    myBatisConfig.setReturnInstanceForEmptyRow(true);
    myBatisConfig.getTypeAliasRegistry().registerAlias(char[].class);
    myBatisConfig.setObjectFactory(new DefaultObjectFactory()
    {
      @Override
      public <T> boolean isCollection(final Class<T> type) {
        // modify MyBatis behaviour to let it map collection results to Iterable
        return Iterable.class.isAssignableFrom(type) || super.isCollection(type);
      }

      @Override
      protected Class<?> resolveInterface(final Class<?> type) {
        if (type == Continuation.class) {
          return ContinuationArrayList.class;
        }
        return super.resolveInterface(type);
      }
    });

    return myBatisConfig;
  }

  /**
   * Register common {@link TypeHandler}s.
   */
  @SuppressWarnings("unchecked")
  private void registerCommonTypeHandlers(boolean isContentStore) {
    boolean lenient = configurePlaceholderTypes(sessionFactory.getConfiguration());

    // register raw/simple mappers first
    register(new ListTypeHandler());
    register(new SetTypeHandler());
    register(new MapTypeHandler());
    register(new DateTimeTypeHandler());

    // mapping of entity ids needs some extra handling
    TypeHandler entityIdHandler = new EntityUUIDTypeHandler(lenient);
    register(EntityUUID.class, entityIdHandler);
    register(EntityId.class, entityIdHandler);
    if (lenient) {
      // also support querying by UUID string when database may not have a UUID type
      register(new LenientUUIDTypeHandler());
    }

    // generate new entity ids on-demand
    register(new EntityInterceptor(frozenMarker));

    if (!isContentStore) {

      // security handlers that only exist in the config store
      register(new PasswordCharacterArrayTypeHandler(passwordHelper));
      register(new PrincipalCollectionTypeHandler());
      registerDetached(new EncryptedStringTypeHandler()); // detached so it doesn't apply to all Strings

      // enable automatic encryption of sensitive JSON fields in the config store
      sensitiveAttributeFilter = buildSensitiveAttributeFilter(sessionFactory.getConfiguration());
    }

    // finally register more complex mappers on top of the raw/simple mappers - this way we can have
    // automatic encryption on by default while individual DAOs can choose to use the raw mapper(s)
    // (for example if they handle encryption themselves outside of the persistence layer)

    register(new AttributesTypeHandler());
    register(new NestedAttributesMapTypeHandler());
  }

  /**
   * Loads the MyBatis configuration for this store.
   */
  @VisibleForTesting
  protected Configuration loadMyBatisConfiguration(final Environment environment) throws IOException {
    if (directories == null) {
      info("Using default MyBatis configuration");
      return new Configuration();
    }

    Path configPath = myBatisConfigPath(environment);

    info("Loading MyBatis configuration from {}", configPath);
    try (InputStream in = newInputStream(configPath); TcclBlock block = begin(getClass())) {
      return new XMLConfigBuilder(in, null, new Properties()).parse();
    }
  }

  /**
   * Returns the path to the MyBatis configuration to use for this store.
   */
  private Path myBatisConfigPath(final Environment environment) {
    Path fabricPath = directories.getConfigDirectory("fabric").toPath();
    Path configPath = fabricPath.resolve(environment.getId() + "-store-mybatis.xml");
    if (!exists(configPath)) {
      // fallback to the shared MyBatis configuration
      configPath = fabricPath.resolve("mybatis.xml");
    }
    return configPath;
  }

  /**
   * Register simple package-less aliases for all parameter and return types in the DAO.
   */
  private void registerSimpleAliases(final Class<? extends DataAccess> accessType) {
    TypeAliasRegistry registry = sessionFactory.getConfiguration().getTypeAliasRegistry();
    TypeLiteral<?> resolvedType = TypeLiteral.get(accessType);
    for (Method method : accessType.getMethods()) {
      for (TypeLiteral<?> parameterType : resolvedType.getParameterTypes(method)) {
        registerSimpleAlias(registry, parameterType.getRawType());
      }
      registerSimpleAlias(registry, resolvedType.getReturnType(method).getRawType());
    }
  }

  /**
   * Registers a simple package-less alias for the resolved type.
   */
  private void registerSimpleAlias(final TypeAliasRegistry registry, final Class<?> clazz) {
    if (!clazz.isPrimitive() && !clazz.getName().startsWith("java.")) {
      try {
        registry.registerAlias(clazz);
      }
      catch (RuntimeException | LinkageError e) {
        debug("Unable to register type alias", e);
      }
    }
  }

  /**
   * Registers the {@link DataAccess} type with MyBatis.
   */
  private void registerDataAccessMapper(final Class<? extends DataAccess> accessType) {
    Class<?> templateType = findTemplateType(accessType);
    if (templateType != null) {
      registerTemplatedMapper(accessType, templateType);
    }
    else {
      registerSimpleMapper(accessType);
    }
    info(REGISTERED_MESSAGE, accessType.getSimpleName());
  }

  /**
   * Searches the directly declared interfaces for one annotated with {@link SchemaTemplate}.
   */
  @Nullable
  private Class<?> findTemplateType(Class<? extends DataAccess> accessType) {
    for (Class<?> candidate : accessType.getInterfaces()) {
      if (candidate.isAnnotationPresent(SchemaTemplate.class)) {
        return candidate;
      }
    }
    return null; // accessType does not directly extend an interface annotated with @SchemaTemplate
  }

  /**
   * Registers a simple {@link DataAccess} type with MyBatis.
   */
  private void registerSimpleMapper(final Class<? extends DataAccess> accessType) {

    // make sure any types expected by the access type are registered first
    Expects expects = accessType.getAnnotation(Expects.class);
    if (expects != null) {
      asList(expects.value()).forEach(this::register);
    }

    // MyBatis will load the corresponding XML schema
    sessionFactory.getConfiguration().addMapper(accessType);
  }

  /**
   * Registers a templated {@link DataAccess} type with MyBatis.
   */
  @SuppressWarnings("unchecked")
  private void registerTemplatedMapper(final Class<? extends DataAccess> accessType, final Class<?> templateType) {

    // make sure any types expected by the template are registered first
    Expects expects = templateType.getAnnotation(Expects.class);
    if (expects != null) {
      asList(expects.value()).forEach(expectedType -> {
        if (expectedType.isAnnotationPresent(SchemaTemplate.class)) {
          // also a template which we need to resolve using the current context
          register(expectedAccessType(accessType, templateType, expectedType));
        }
        else {
          register(expectedType);
        }
      });
    }

    // lower-case prefix of the access type, excluding the package; for example MavenAssetDAO / AssetDAO = maven
    String prefix = extractPrefix(accessType.getSimpleName(), templateType.getSimpleName());

    // the variable in the schema XML that we'll replace with the local prefix
    String placeholder = templateType.getAnnotation(SchemaTemplate.class).value();

    // load and populate the template's mapper XML
    String xml = loadMapperXml(templateType, true)
        .replace("${namespace}", accessType.getName())
        .replace("${" + placeholder + '}', prefix);

    // now append the access type's mapper XML if it has one (this is where it can define extra fields/methods)
    String includeXml = loadMapperXml(accessType, false);
    Matcher mapperBody = MAPPER_BODY.matcher(includeXml);
    if (mapperBody.find()) {
      xml = xml.replace("</mapper>", format("<!-- %s -->%s</mapper>", accessType.getName(), mapperBody.group(1)));
    }

    try {
      log.trace(xml);

      Configuration mybatisConfig = sessionFactory.getConfiguration();

      XMLMapperBuilder xmlParser = new XMLMapperBuilder(
          new ByteArrayInputStream(xml.getBytes(UTF_8)),
          mybatisConfig,
          mapperXmlPath(templateType) + "${" + placeholder + '=' + prefix + '}',
          mybatisConfig.getSqlFragments(),
          accessType.getName());

      xmlParser.parse();

      // when the type is not visible from this classloader then MyBatis will prepare the mapper
      // but not do the final registration - so this call is needed to complete the registration
      if (!mybatisConfig.hasMapper(accessType)) {
        mybatisConfig.addMapper(accessType);
      }
    }
    catch (RuntimeException e) {
      log.warn(xml, e);
      throw e;
    }
  }

  /**
   * Finds the expected access type for the expected template type, given the current access and template types.
   * To simplify matters we assume the current and expected access types are in the same package and classloader.
   */
  private Class expectedAccessType(final Class currentAccessType,
                                   final Class currentTemplateType,
                                   final Class expectedTemplateType)
  {
    String currentSuffix = currentTemplateType.getSimpleName();
    String expectedSuffix = expectedTemplateType.getSimpleName();

    // org.example.MavenAssetDAO / AssetDAO / ComponentDAO = org.example.MavenComponentDAO
    String expectedAccessName = currentAccessType.getName().replace(currentSuffix, expectedSuffix);
    Class expectedAccessType;
    try {
      expectedAccessType = currentAccessType.getClassLoader().loadClass(expectedAccessName);
    }
    catch (Exception | LinkageError e) {
      throw new TypeNotPresentException(expectedAccessName, e);
    }

    // sanity check that this type has the expected class hierarchy
    checkArgument(expectedTemplateType.isAssignableFrom(expectedAccessType),
        "%s must extend %s", expectedAccessType, expectedTemplateType);

    return expectedAccessType;
  }

  /**
   * Returns the lower-case form of the prefix after removing templateName from accessName.
   */
  private String extractPrefix(final String accessName, final String templateName) {
    checkArgument(accessName.endsWith(templateName), "%s must end with %s", accessName, templateName);
    String prefix = lower(accessName.substring(0, accessName.length() - templateName.length()));
    checkArgument(!prefix.isEmpty(), "%s must add a prefix to %s", accessName, templateName);
    return prefix;
  }

  /**
   * Absolute resource path to the type's mapper XML.
   */
  private String mapperXmlPath(final Class type) {
    return '/' + type.getName().replace('.', '/') + ".xml";
  }

  /**
   * Attempts to load the type's mapper XML. If the XML is missing and required then it throws
   * {@link IllegalArgumentException}. If it is not required then it returns the empty string.
   */
  private String loadMapperXml(final Class type, final boolean required) {
    URL resource = type.getResource(mapperXmlPath(type));
    checkArgument(!required || resource != null, "XML resource for %s is missing", type);
    try {
      return resource != null ? Resources.toString(resource, UTF_8) : "";
    }
    catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + resource, e);
    }
  }

  /**
   * Registers the given {@link Interceptor} with MyBatis.
   */
  @VisibleForTesting
  public void register(final Interceptor interceptor) {
    sessionFactory.getConfiguration().addInterceptor(interceptor);
    info(REGISTERED_MESSAGE, interceptor.getClass().getSimpleName());
  }

  /**
   * Registers the given {@link TypeHandler} with MyBatis.
   */
  @VisibleForTesting
  public void register(final TypeHandler<?> handler) {
    prepare(handler).register(handler);
    info(REGISTERED_MESSAGE, handler.getClass().getSimpleName());
  }

  /**
   * Registers the given {@link TypeHandler} with MyBatis binding it to an explicit type.
   */
  private <T> void register(final Class<T> type, final TypeHandler<? extends T> handler) {
    prepare(handler).register(type, handler);
    info(REGISTERED_MESSAGE, handler.getClass().getSimpleName() + " (" + type.getSimpleName() + ")");
  }

  /**
   * Registers the given {@link TypeHandler} with MyBatis without binding it to any type.
   */
  private <T> void registerDetached(final TypeHandler<? extends T> handler) {
    prepare(handler).register(null, null, handler);
    info(REGISTERED_MESSAGE, handler.getClass().getSimpleName() + " (detached)");
  }

  /**
   * Prepare the {@link TypeHandler} for use with this datastore.
   */
  private TypeHandlerRegistry prepare(final TypeHandler<?> handler) {
    if (handler instanceof CipherAwareTypeHandler<?>) {
      ((CipherAwareTypeHandler<?>) handler).setCipher(databaseCipher);
    }
    if (sensitiveAttributeFilter != null && handler instanceof AbstractJsonTypeHandler<?>) {
      ((AbstractJsonTypeHandler<?>) handler).encryptSensitiveFields(passwordHelper, sensitiveAttributeFilter);
    }
    Configuration mybatisConfig = sessionFactory.getConfiguration();
    registerSimpleAlias(mybatisConfig.getTypeAliasRegistry(), handler.getClass());
    return mybatisConfig.getTypeHandlerRegistry();
  }

  /**
   * Tracks and registers custom {@link TypeHandler}s with MyBatis.
   */
  static class TypeHandlerMediator
      implements Mediator<Named, TypeHandler, MyBatisDataStore>
  {
    private final boolean isContentStore;

    TypeHandlerMediator(final boolean isContentStore) {
      this.isContentStore = isContentStore;
    }

    @Override
    public void add(final BeanEntry<Named, TypeHandler> entry, final MyBatisDataStore store) {
      if (isContentStore == entry.getValue() instanceof ContentTypeHandler<?>) {
        store.register(entry.getValue());
      }
    }

    @Override
    public void remove(final BeanEntry<Named, TypeHandler> entry, final MyBatisDataStore store) {
      // unregistration of custom type handlers is not supported
    }
  }
}