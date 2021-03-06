package org.jpos.qi.eeuser;

import com.vaadin.data.provider.ConfigurableFilterDataProvider;
import com.vaadin.data.provider.Query;
import com.vaadin.shared.ui.MarginInfo;
import com.vaadin.ui.*;
import com.vaadin.ui.themes.ValoTheme;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Base64;
import org.jpos.crypto.CryptoService;
import org.jpos.crypto.SecureData;
import org.jpos.ee.*;
import org.jpos.qi.ConfirmDialog;
import org.jpos.qi.QIEntityView;
import org.jpos.qi.QIHelper;
import org.jpos.util.NameRegistrar;
import org.jpos.util.QIUtils;
import org.jpos.util.Serializer;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;


/**
 * Created by jr on 9/11/17.
 */
public class ConsumersView extends QIEntityView<Consumer> {

    private static final String HASH_ALGORITHM = "HmacSHA256";
    private ComboBox<User> userComboBox;
    private User selectedUser;

    public ConsumersView() {
        super(Consumer.class, "consumers");
    }

    @Override
    protected HorizontalLayout createHeader (String title) {
        HorizontalLayout header;
        if (isGeneralView()) {
            VerticalLayout main = new VerticalLayout();
            main.setMargin(false);
            HorizontalLayout hl = super.createHeader(title);
            main.addComponent(hl);
            main.addComponent(createUserPanel());
            header = new HorizontalLayout(main);
        } else {
            header = super.createHeader(title);
        }
        header.setWidth("100%");
        return header;
    }

    @Override
    public void showSpecificView (final String parameter) {
        String[] params = parameter.split("\\?user=");
        if (params.length > 1) {
            String userId = params[1];
            try {
                this.selectedUser = (User) DB.exec(db -> {
                    UserManager mgr = new UserManager(db);
                    return mgr.getItemByParam("id",userId,false);
                });
            } catch (Exception e) {
                getApp().getLog().error(e);
            }
            super.showSpecificView(parameter);
        } else if (parameter.contains("new")){
            getApp().displayError("Invalid User","Must select a User");
            getApp().getNavigator().navigateTo(getGeneralRoute());
        } else {
            super.showSpecificView(parameter);
        }
    }

    @Override
    protected void navigateToNewRoute() {
        getApp().getNavigator().navigateTo(getGeneralRoute() + "/new?user=" + this.selectedUser.getId());
    }

    private HorizontalLayout createUserPanel() {
        HorizontalLayout hl = new HorizontalLayout();
        hl.setMargin(new MarginInfo(false,true,true,true));
        hl.setSpacing(true);
        userComboBox = createUserBox();
        userComboBox.setStyleName(ValoTheme.COMBOBOX_SMALL);
        userComboBox.addValueChangeListener(listener -> {
            ConfigurableFilterDataProvider wrapper = (ConfigurableFilterDataProvider) getGrid().getDataProvider();
            wrapper.setFilter(listener.getValue());
            this.selectedUser = listener.getValue();
            wrapper.refreshAll();
        });
        hl.addComponent(userComboBox);
        return hl;
    }

    private ComboBox<User> createUserBox() {
        ComboBox<User> box = new ComboBox(QIUtils.getCaptionFromId("user"));
        box.setItemCaptionGenerator(User::getNickAndId);
        UsersHelper usersHelper = new UsersHelper();
        box.setDataProvider(usersHelper.getDataProvider());
        box.setEmptySelectionAllowed(false);
        return box;
    }

    @Override
    public void setGridGetters() {
        Grid<Consumer> g = getGrid();
        g.addColumn(Consumer::getId).setId("id");
        g.addColumn(consumer -> consumer.getRolesAsString()).setId("roles");
        g.addColumn(Consumer::getStartDate).setId("startDate");
        g.addColumn(Consumer::getEndDate).setId("endDate");
        g.addColumn(consumer -> consumer.getUser().getNickAndId()).setId("user");
        g.addColumn(Consumer::isActive).setId("active");
        g.addColumn(Consumer::isDeleted).setId("deleted");

        //select first item on user combobox
        userComboBox.setValue(userComboBox.getDataProvider().fetch(new Query<>()).findFirst().orElse(null));
    }

    @Override
    public QIHelper createHelper() {
        return new ConsumersHelper(Consumer.class);
    }

    @Override
    public Object getEntity(Object entity) {
        if(entity instanceof Consumer) {
            Consumer u = (Consumer) entity;
            if(u.getId() != null) {
                return getHelper().getEntityByParam(String.valueOf(u.getId()));
            }
        }
        return null;
    }

    @Override
    public String getHeaderSpecificTitle(Object entity) {
        if (entity instanceof Consumer) {
            Consumer u = (Consumer) entity;
            return u.getId() != null ? u.getId() : "New";
        } else {
            return null;
        }
    }

    protected Component buildAndBindCustomComponent(String propertyId) {
        if ("roles".equalsIgnoreCase(propertyId)) {
            CheckBoxGroup<Role> checkBoxGroup = new CheckBoxGroup<>(QIUtils.getCaptionFromId(propertyId));
            checkBoxGroup.setItems(((ConsumersHelper)getHelper()).getRoles());
            checkBoxGroup.setItemCaptionGenerator(role -> StringUtils.capitalize(role.getName()));
            formatField(propertyId,checkBoxGroup).bind(propertyId);
            return checkBoxGroup;
        }
        if ("user".equalsIgnoreCase(propertyId)) {
            ComboBox<User> box = createUserBox();
            formatField(propertyId,box).bind(propertyId);
            box.setEnabled(false);
            box.setValue(this.selectedUser);
            return box;
        }
        if ("startdate".equalsIgnoreCase(propertyId) || "endDate".equalsIgnoreCase(propertyId)) {
            return buildAndBindDateField(propertyId);
        }
        return null;
    }

    public void saveEntity () throws BLException {
        Consumer c = getInstance();
        c.setUser(this.selectedUser);
        Map<String,String> smap = new HashMap<>();
        try{
            smap.put("S", Base64.toBase64String(generateKey().getEncoded()));
            SecureData sd = getCryptoService().aesEncrypt(Serializer.serialize(smap));
            c.setKid(sd.getId());
            c.setSecureData(sd.getEncoded());
        } catch (IOException e) {
            e.printStackTrace();
        } catch (NameRegistrar.NotFoundException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }


        getApp().addWindow(new ConfirmDialog(
                getApp().getMessage("secretTitle"),
                getApp().getMessage("secretDescription","el secreto"),
                getApp().getMessage("secretConfirm"),
                getApp().getMessage("cancel"),
                confirm -> {
                    if (confirm) {
                        super.saveEntity();
                    }
                }));
    }

    @Override
    public boolean canEdit() {
        return true;
    }
    @Override
    public boolean canAdd() {return true;}
    public boolean canRemove() {return true;}

    private CryptoService getCryptoService() throws NameRegistrar.NotFoundException {
       return (CryptoService) NameRegistrar.get("crypto-service");
    }

    private SecretKey generateKey () throws NoSuchAlgorithmException {
        KeyGenerator gen = KeyGenerator.getInstance(HASH_ALGORITHM);
        return gen.generateKey();
    }
}
