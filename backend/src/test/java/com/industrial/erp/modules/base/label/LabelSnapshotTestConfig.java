package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateHistMapper;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mockito.Mockito;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Task 6 集成测试的**最小** Spring 上下文 (docs/36 Step 2)。
 *
 * <p>刻意不是 {@code @SpringBootTest}：那份上下文会连 Redis、版本初始化、生产 preflight 和全部
 * {@code @Component} 一起拉起来，测试跑绿也说不清到底是谁的行为。这里只注册 brief 点名的那几个 bean：
 * <ul>
 *   <li>{@link DriverManagerDataSource} —— 每次取连接都是真连接，正好用来在事务之外回读已提交状态；</li>
 *   <li>{@link DataSourceTransactionManager} —— 让 {@code SELECT ... FOR UPDATE} 落在 autoCommit=false 上，
 *       行锁才真的存在；</li>
 *   <li>{@link MybatisSqlSessionFactoryBean} + 两个 {@link MapperFactoryBean} —— 生产同款 SQL 通路；</li>
 *   <li>真实 {@link LabelTemplateRenderer}（渲染器不 mock，否则「过闸」这件事是假的）；</li>
 *   <li>mock {@link PermissionService}（只授予 {@code base:label:template} 并返回用户 42，不碰 Sa-Token 会话）；</li>
 *   <li>真实 {@link BaseLabelTemplateService}，由 {@code @EnableTransactionManagement} 代理。</li>
 * </ul>
 *
 * <p>{@code proxyTargetClass = true}：服务没有接口，JDK 动态代理会退化成 CGLIB，这里直接写明，
 * 免得以后有人加了接口就悄悄换掉代理方式、{@code update()} 的事务注释也跟着失效。
 *
 * <p>**这个类故意不带 {@code @Configuration}（lite mode），别顺手加回去。** 它是个顶层类，位置就在
 * {@code @SpringBootApplication} 的扫描根 {@code com.industrial.erp} 底下，一旦带上 {@code @Configuration}
 * （= 元标注 {@code @Component}）就会被**别的全量上下文 IT 的组件扫描**捞走 ——
 * {@code InvLedgerQueryMapperMysqlIT} 实测因此起不来：
 * {@code ConflictingBeanDefinitionException: Annotation-specified bean name 'baseLabelTemplateHistMapper'
 * ... conflicts with ...} —— 本类的 {@code @Bean} 方法和生产 {@code @MapperScan("com.industrial.erp.modules.**.mapper")}
 * 抢同一个 bean 名，而且那份上下文还会硬要找 {@code label.p1.mysql.url} 这个只有本 IT 才有的属性。
 * {@code @ContextConfiguration(classes = ...)} 会把任何类当配置类处理（{@code @Bean} 方法照常生效），
 * 不需要注解；代价是没有 {@code proxyTargetClass} 的 {@code @Bean} 方法互调代理 —— 本类所有依赖都走**方法参数**注入，
 * 没有方法间调用，所以 lite mode 完全够用。
 */
@EnableTransactionManagement(proxyTargetClass = true)
class LabelSnapshotTestConfig {

    @Bean
    DataSource dataSource(@Value("${label.p1.mysql.url}") String url,
                          @Value("${label.p1.mysql.user}") String user,
                          @Value("${label.p1.mysql.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    @Bean
    DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * MyBatis-Plus 的 SqlSessionFactory。
     *
     * <p>**必须注册生产同款拦截器**（{@code MybatisPlusConfig.mybatisPlusInterceptor} 里那三条：
     * 分页 / 乐观锁 / 防全表删除）。2026-11-07 Task 14 全范围评审揪出的 B02 生产故障
     * 「update 落快照后 {@code deleteExcept} 报 {@code MybatisPlusException: Failed to process}」
     * 就是生产 {@code BlockAttackInnerInterceptor} 拦住了多表形态的
     * {@code DELETE … AS hist FORCE INDEX (…)} 而这里没有 ⇒ IT 假绿、B02 用例才兜出
     * 来；本 fixture 不带上这三条，就永远测不到「真实 SQL 经过拦截器后再发」这一层，
     * 下一轮再改 SQL 又出同类事故。对齐方式：三个 inner 拦截器**逐个等价**照抄，
     * {@code pagination} 的 {@code maxLimit=200 / overflow=false} 保持与生产一致
     * （本 IT 只断言行内容，不靠 SQL 改写语义，参数对齐只是保险）。
     */
    @Bean
    SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        factoryBean.addPlugins(interceptor);

        factoryBean.afterPropertiesSet();

        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, BaseLabelTemplate.class);
        TableInfoHelper.initTableInfo(assistant, BaseLabelTemplateHist.class);

        return factoryBean.getObject();
    }

    @Bean
    MapperFactoryBean<BaseLabelTemplateMapper> baseLabelTemplateMapper(SqlSessionFactory sessionFactory) {
        MapperFactoryBean<BaseLabelTemplateMapper> bean = new MapperFactoryBean<>(BaseLabelTemplateMapper.class);
        bean.setSqlSessionFactory(sessionFactory);
        return bean;
    }

    @Bean
    MapperFactoryBean<BaseLabelTemplateHistMapper> baseLabelTemplateHistMapper(SqlSessionFactory sessionFactory) {
        MapperFactoryBean<BaseLabelTemplateHistMapper> bean = new MapperFactoryBean<>(BaseLabelTemplateHistMapper.class);
        bean.setSqlSessionFactory(sessionFactory);
        return bean;
    }

    /**
     * 渲染器用真家伙（{@link com.industrial.erp.modules.base.label.LabelTemplateRenderer}）。
     * 它是普通类不是接口，这里保留真实类型给读代码的人一眼看出 mock 的只有权限。
     */
    @Bean
    LabelTemplateRenderer labelTemplateRenderer() {
        return new LabelTemplateRenderer();
    }

    /**
     * 只有权限是 mock：授予 {@code base:label:template}，当前用户固定 42。
     *
     * <p>历史行的 {@code create_by} 由服务在快照时刻调 {@code getCurrentUserId()} 取，
     * 不是「把历史实体 mock 出来」（那样测的是 mock，不是写路径）。
     */
    @Bean
    PermissionService permissionService() {
        return Mockito.mock(PermissionService.class);
    }

    /**
     * Task 7 起服务构造器要 {@code SysUserMapper}（history 摘要的姓名注入）。IT 的一次性库里
     * **没有 sys_user 表**（ThrowawayMysql 只建 sql/155/158 的两张表，改它不在本任务授权内），
     * 所以这里继续用 mock：默认空列表 ⇒ createByName 全 null，正是「姓名缺失时沿用注入器回落、
     * UI 还能显示 createBy」那条路径。批量取名本身由 LabelSnapshotTest 的单测钉。
     */
    @Bean
    SysUserMapper sysUserMapper() {
        return Mockito.mock(SysUserMapper.class);
    }

    /** 真实服务；因为 {@code @EnableTransactionManagement}，注入到测试里的这个 bean 是代理。 */
    @Bean
    BaseLabelTemplateService baseLabelTemplateService(BaseLabelTemplateMapper mapper,
                                                       LabelTemplateRenderer renderer,
                                                       PermissionService permService,
                                                       BaseLabelTemplateHistMapper histMapper,
                                                       SysUserMapper userMapper) {
        return new BaseLabelTemplateService(mapper, renderer, permService, histMapper, userMapper);
    }

    /** 测试自己灌小数据、以及**在事务之外**回读已提交状态用。 */
    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
